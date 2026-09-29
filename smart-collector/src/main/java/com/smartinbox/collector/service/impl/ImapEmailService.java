package com.smartinbox.collector.service.impl;

import com.smartinbox.collector.dto.EmailDTO;
import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.service.EmailService;
import com.smartinbox.collector.service.MailSyncReport;
import com.smartinbox.collector.util.PendingMailIndex;
import com.smartinbox.collector.util.EmailParser;
import jakarta.annotation.PostConstruct;
import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ImapEmailService implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(ImapEmailService.class);

    // Collect the complete rolling window; source-mailbox read state is irrelevant.
    private static final int WINDOW_HOURS = com.smartinbox.collector.util.MailWindow.HOURS;
    private static final String MQ_TOPIC = "EMAIL_RAW_TOPIC";

    // 固定落盘目录（避免相对路径导致“换工作目录=读不到文件=重复 ingest”）
    private static final String APP_DIR_NAME = ".smart-inbox";
    private static final String PROCESSED_EMAILS_FILENAME = "processed_emails.txt";

    @Value("${spring.mail.host:imap.gmail.com}")
    private String host;

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    @Value("${spring.mail.protocol:imap}")
    private String protocol;

    @Value("${email.qq.host}")
    private String qqHost;

    @Value("${email.qq.username}")
    private String qqUsername;

    @Value("${email.qq.password}")
    private String qqPassword;

    private final RocketMQTemplate rocketMQTemplate;
    private final CredentialVaultService credentialVault;

    // 进程内去重 + 跨重启去重（文件）
    private final Set<String> processedEmailIds = Collections.synchronizedSet(new HashSet<>());
    private Path processedFilePath;
    @org.springframework.beans.factory.annotation.Autowired private MailSyncIndex syncIndex;
    private final Map<String, PendingMailIndex> pendingBySource = new HashMap<>();

    public ImapEmailService(RocketMQTemplate rocketMQTemplate, CredentialVaultService credentialVault) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.credentialVault = credentialVault;
    }

    @PostConstruct
    public void init() {
        initProcessedFilePath();
        loadProcessedEmailIds();
    }

    private void initProcessedFilePath() {
        String userHome = System.getProperty("user.home");
        Path appDir = Paths.get(userHome, APP_DIR_NAME);
        try {
            Files.createDirectories(appDir);
        } catch (Exception e) {
            logger.warn("Failed to create app dir {}: {}", appDir, e.getMessage());
        }
        processedFilePath = appDir.resolve(PROCESSED_EMAILS_FILENAME);
        logger.info("Email processed file path: {}", processedFilePath.toAbsolutePath());
    }

    private void loadProcessedEmailIds() {
        try {
            if (processedFilePath == null)
                initProcessedFilePath();
            if (!Files.exists(processedFilePath)) {
                logger.info("No processed emails file found, starting fresh.");
                return;
            }
            List<String> lines = Files.readAllLines(processedFilePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                String id = line.trim();
                if (!id.isEmpty())
                    processedEmailIds.add(id);
            }
            logger.info("Loaded {} processed email IDs.", processedEmailIds.size());
        } catch (Exception e) {
            logger.error("Failed to load processed email IDs", e);
        }
    }

    private synchronized void saveProcessedEmailId(String id) {
        try {
            if (processedEmailIds.contains(id)) return;
            if (processedFilePath == null)
                initProcessedFilePath();
            Files.write(processedFilePath,
                    (id + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            processedEmailIds.add(id);
        } catch (Exception e) {
            logger.error("Failed to save processed email ID", e);
        }
    }

    private static long extractEmailTimeMillis(Message message) {
        try {
            Date received = message.getReceivedDate();
            if (received != null)
                return received.getTime();
        } catch (Exception ignored) {
        }
        try {
            Date sent = message.getSentDate();
            if (sent != null)
                return sent.getTime();
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String safeDecode(String s) {
        if (s == null)
            return "";
        try {
            return MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * ✅ Stable ExternalId:
     * - Prefer Message-ID (best)
     * - Fallback: subject|sender|time
     * Returned value is MD5 hex string.
     */
    private static String stableUniqueId(Message message, String subject, String sender, long emailTimeMs) {
        try {
            String[] mid = message.getHeader("Message-ID");
            if (mid != null && mid.length > 0 && mid[0] != null && !mid[0].isBlank()) {
                return org.springframework.util.DigestUtils.md5DigestAsHex(
                        mid[0].trim().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        String fallback = subject + "|" + sender + "|" + emailTimeMs;
        return org.springframework.util.DigestUtils.md5DigestAsHex(fallback.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void fetchRecentEmails() {
        fetchSource("GMAIL");
        fetchSource("QQMAIL");
    }

    private PendingMailIndex pending(String source) {
        if (processedFilePath == null) initProcessedFilePath();
        return pendingBySource.computeIfAbsent(source, ignored -> new PendingMailIndex(processedFilePath.resolveSibling(processedFilePath.getFileName() + "." + source)));
    }

    @Override
    public synchronized MailSyncReport fetchSource(String source) {
        if (!Set.of("GMAIL", "QQMAIL").contains(source)) throw new IllegalArgumentException("Unknown IMAP channel");
        long cutoffMs = System.currentTimeMillis() - WINDOW_HOURS * 3600_000L;
        Date cutoffDate = new Date(cutoffMs);
        AccountConfig account = "GMAIL".equals(source)
                ? new AccountConfig(host, credentialVault.value("gmail.username", username), credentialVault.value("gmail.password", password), source)
                : new AccountConfig(qqHost, credentialVault.value("qq.username", qqUsername), credentialVault.value("qq.password", qqPassword), source);
        if (account.username == null || account.username.isBlank()) return MailSyncReport.failed("imap", "not_configured");
        var durable = syncIndex == null ? MailSyncIndex.Snapshot.unavailable() : syncIndex.snapshot(source);
        // Redirected Outlook mail can live in this transport mailbox; reconcile its own source too.
        var redirected = syncIndex == null ? MailSyncIndex.Snapshot.unavailable() : syncIndex.snapshot("OUTLOOK");
        var scan = collectCandidates(account, cutoffDate, cutoffMs, durable, redirected);
        List<Candidate> candidates = scan.candidates.stream()
                .sorted(Comparator.comparingLong((Candidate c) -> c.emailTimeMs).reversed())
                .collect(Collectors.toList());
        int published = 0;
        String failure = scan.failure;
        for (Candidate c : candidates) {
            try {
                rocketMQTemplate.convertAndSend(MQ_TOPIC, c.dto);
                pending(source).published(c.uniqueId);
                saveProcessedEmailId(c.uniqueId);
                saveProcessedEmailId("html-v1:" + c.uniqueId);
                published++;
            } catch (Exception e) {
                failure = "publish_failed";
            }
        }
        return new MailSyncReport(failure.isBlank(), failure.isBlank() ? "connected" : "failed", "imap", scan.scanned,
                published, scan.bodyFetched, scan.skipped, failure, durable.available());
    }

    private ScanResult collectCandidates(AccountConfig account, Date cutoffDate, long cutoffMs,
            MailSyncIndex.Snapshot durable, MailSyncIndex.Snapshot redirected) {
        List<Candidate> out = new ArrayList<>();
        int scanned = 0, skipped = 0, fetched = 0;
        String failure = "";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail.imap.host", account.host);
        props.put("mail.imap.port", "993");
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        props.put("mail.imap.writetimeout", "30000");

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(account.host, account.username, account.password);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            SearchTerm recent = new ReceivedDateTerm(ComparisonTerm.GE, cutoffDate);

            Message[] messages;
            try {
                messages = inbox.search(recent);
            } catch (Exception e) {
                logger.warn("[{}] Server-side date search failed, fallback to local filter: {}",
                        account.sourceName, e.getMessage());
                messages = inbox.getMessages();
            }

            logger.info("[{}] Date-window query returned: {}", account.sourceName, messages.length);

            for (Message message : messages) {
                long emailTimeMs = extractEmailTimeMillis(message);
                if (emailTimeMs < cutoffMs)
                    continue; // 本地兜底过滤
                scanned++;

                String subject = safeDecode(message.getSubject());
                String sender = "";
                try {
                    Address[] from = message.getFrom();
                    if (from != null && from.length > 0)
                        sender = safeDecode(from[0].toString());
                } catch (Exception ignored) {
                }

                String uniqueId = stableUniqueId(message, subject, sender, emailTimeMs);
                String sourceName = redirectedOutlookSource(message, account.sourceName);
                var stored = "OUTLOOK".equals(sourceName) ? redirected : durable;
                if (stored.complete(uniqueId) || pending(account.sourceName).recentlyPublished(uniqueId)) { skipped++; continue; }

                EmailParser.Body body;
                try {
                    body = EmailParser.parse(message);
                    fetched++;
                } catch (Exception e) {
                    failure = "body_parse_failed";
                    continue;
                }

                // Outlook Web can redirect Waterloo mail into an already connected mailbox.
                // Redirected messages keep the original recipient, so retain them as a separate channel.
                // ✅ externalId = uniqueId（Message-ID hash / fallback hash）
                EmailDTO dto = new EmailDTO(subject, sender, body.text(), emailTimeMs, sourceName, uniqueId);
                dto.setHtmlContent(body.html());

                out.add(new Candidate(dto, uniqueId, emailTimeMs));
            }

            inbox.close(false);
            store.close();

        } catch (Exception e) {
            failure = e instanceof AuthenticationFailedException ? "authentication_failed" : "imap_sync_failed";
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
        }

        logger.info("[{}] Candidates within {} hours = {}", account.sourceName, WINDOW_HOURS, out.size());
        return new ScanResult(out, scanned, skipped, fetched, failure);
    }

    private record ScanResult(List<Candidate> candidates, int scanned, int skipped, int bodyFetched, String failure) {}

    private String redirectedOutlookSource(Message message, String fallbackSource) {
        if ("OUTLOOK".equalsIgnoreCase(fallbackSource)) return fallbackSource;
        String outlookAddress = credentialVault.value("outlook.username", "");
        if (outlookAddress == null || outlookAddress.isBlank()) return fallbackSource;
        try {
            Address[] recipients = message.getAllRecipients();
            if (recipients != null) {
                for (Address recipient : recipients) {
                    String value = recipient == null ? "" : recipient.toString();
                    if (value.toLowerCase(Locale.ROOT).contains(outlookAddress.toLowerCase(Locale.ROOT))) {
                        return "OUTLOOK";
                    }
                }
            }
            for (String headerName : List.of("X-Original-To", "X-Envelope-To", "Envelope-To")) {
                String[] values = message.getHeader(headerName);
                if (values != null && Arrays.stream(values).anyMatch(value -> value != null
                        && value.toLowerCase(Locale.ROOT).contains(outlookAddress.toLowerCase(Locale.ROOT)))) {
                    return "OUTLOOK";
                }
            }
        } catch (Exception exception) {
            logger.debug("Could not inspect original recipients for redirected Outlook mail: {}", exception.getMessage());
        }
        return fallbackSource;
    }

    private static class Candidate {
        EmailDTO dto;
        String uniqueId;
        long emailTimeMs;

        Candidate(EmailDTO dto, String uniqueId, long emailTimeMs) {
            this.dto = dto;
            this.uniqueId = uniqueId;
            this.emailTimeMs = emailTimeMs;
        }
    }

    private static class AccountConfig {
        String host;
        String username;
        String password;
        String sourceName;

        AccountConfig(String host, String username, String password, String sourceName) {
            this.host = host;
            this.username = username;
            this.password = password;
            this.sourceName = sourceName;
        }
    }
}

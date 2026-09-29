package com.smartinbox.collector.credentials;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local-only vault. Values are never included in list responses or application logs. */
@Service
public class CredentialVaultService {
    private static final List<Entry> ENTRIES = List.of(
            new Entry("gmail.username", "Gmail account", "Email account", "spring.mail.username"),
            new Entry("gmail.password", "Gmail app password", "Email credential", "spring.mail.password"),
            new Entry("qq.username", "QQ Mail account", "Email account", "email.qq.username"),
            new Entry("qq.password", "QQ Mail authorization code", "Email credential", "email.qq.password"),
            new Entry("outlook.username", "Outlook / Microsoft 365 account", "Email account", "outlook.username"),
            new Entry("outlook.client-id", "Microsoft application client ID", "OAuth setting", "outlook.client-id"),
            new Entry("outlook.tenant", "Microsoft tenant", "OAuth setting", "outlook.tenant"),
            new Entry("outlook.refresh-token", "Microsoft OAuth refresh token", "OAuth credential", "outlook.refresh-token"),
            new Entry("rocketmq.access-key", "RocketMQ access key", "App credential", "rocketmq.producer.access-key"),
            new Entry("rocketmq.secret-key", "RocketMQ secret key", "App credential", "rocketmq.producer.secret-key")
    );
    // SHA-256 verifier for the management password. The password itself is never stored or sent to the browser.
    private static final long SESSION_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MILLIS = 5 * 60 * 1000L;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, FailureWindow> failedAttempts = new ConcurrentHashMap<>();
    private Path vaultFile;
    private Path keyFile;
    private SecretKey encryptionKey;

    public CredentialVaultService(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() {
        Path directory = Path.of(System.getProperty("user.home"), ".smart-inbox", "credentials-vault");
        vaultFile = directory.resolve("vault.enc");
        keyFile = directory.resolve("vault.key");
        try {
            Files.createDirectories(directory);
            encryptionKey = loadOrCreateKey();
            for (Entry entry : ENTRIES) {
                String configured = environment.getProperty(entry.property());
                if (configured != null) values.put(entry.id(), configured);
            }
            if (Files.exists(vaultFile)) values.putAll(decrypt(Files.readAllBytes(vaultFile)));
        } catch (Exception exception) {
            throw new IllegalStateException("Credential vault could not be initialized", exception);
        }
    }

    public List<MaskedCredential> listMasked() {
        return ENTRIES.stream().map(entry -> new MaskedCredential(
                entry.id(),
                entry.label(),
                entry.category(),
                mask(values.get(entry.id())),
                requiresRestart(entry.id())
        )).toList();
    }

    public String authorize(String password, String clientId) {
        String client = clientId == null || clientId.isBlank() ? "unknown" : clientId;
        if (isLocked(client)) return null;
        String verifier = environment.getProperty("smart.credentials.management-password-sha256", "");
        if (!verifier.matches("[a-fA-F0-9]{64}") || password == null || !MessageDigest.isEqual(sha256(password), hex(verifier))) {
            failedAttempts.compute(client, (key, current) -> current == null || (current.lockedUntil() > 0 && current.lockedUntil() < System.currentTimeMillis())
                    ? new FailureWindow(1, 0) : current.fail());
            FailureWindow current = failedAttempts.get(client);
            if (current.attempts() >= MAX_FAILED_ATTEMPTS) failedAttempts.put(client, new FailureWindow(current.attempts(), System.currentTimeMillis() + LOCKOUT_MILLIS));
            return null;
        }
        failedAttempts.remove(client);
        String token = UUID.randomUUID().toString();
        sessions.put(token, System.currentTimeMillis() + SESSION_MILLIS);
        return token;
    }

    public boolean isAuthorized(String token) {
        if (token == null) return false;
        Long expiresAt = sessions.get(token);
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) { sessions.remove(token); return false; }
        return true;
    }

    public Optional<String> reveal(String id) { return Optional.ofNullable(values.get(id)); }

    public synchronized boolean update(String id, String value) {
        if (ENTRIES.stream().noneMatch(entry -> entry.id().equals(id)) || value == null || value.isBlank()) return false;
        values.put(id, value);
        try { Files.write(vaultFile, encrypt(values), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); return true; }
        catch (Exception exception) { return false; }
    }

    public String value(String id, String fallback) { return values.getOrDefault(id, fallback); }
    public long expiresInSeconds(String token) { Long expiry = sessions.get(token); return expiry == null ? 0 : Math.max(0, (expiry - System.currentTimeMillis()) / 1000); }
    public boolean requiresRestart(String id) { return !Set.of("gmail.username", "gmail.password", "qq.username", "qq.password", "outlook.username", "outlook.client-id", "outlook.tenant", "outlook.refresh-token").contains(id); }
    boolean isLocked(String clientId) { FailureWindow failures = failedAttempts.get(clientId); if (failures == null || failures.lockedUntil() == 0) return false; if (failures.lockedUntil() <= System.currentTimeMillis()) { failedAttempts.remove(clientId); return false; } return true; }

    private SecretKey loadOrCreateKey() throws Exception {
        if (Files.exists(keyFile)) return new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(Files.readString(keyFile)), "AES");
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()), StandardOpenOption.CREATE_NEW);
        return key;
    }
    private byte[] encrypt(Map<String, String> source) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(objectMapper.writeValueAsBytes(source));
        byte[] result = new byte[iv.length + encrypted.length]; System.arraycopy(iv, 0, result, 0, iv.length); System.arraycopy(encrypted, 0, result, iv.length, encrypted.length); return result;
    }
    private Map<String, String> decrypt(byte[] payload) throws Exception {
        byte[] iv = Arrays.copyOfRange(payload, 0, 12); byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
        return objectMapper.readValue(cipher.doFinal(encrypted), new TypeReference<>() {});
    }
    private static String mask(String value) { if (value == null || value.isBlank()) return "Not configured"; return "••••••••"; }
    private static byte[] sha256(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static byte[] hex(String text) { byte[] output = new byte[text.length() / 2]; for (int i = 0; i < output.length; i++) output[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16); return output; }
    private record Entry(String id, String label, String category, String property) {}
    private record FailureWindow(int attempts, long lockedUntil) { FailureWindow fail() { return new FailureWindow(attempts + 1, lockedUntil); } }
    public record MaskedCredential(String id, String label, String category, String maskedValue, boolean restartRequired) {}
}

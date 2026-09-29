package com.smartinbox.processor.controller;

import com.smartinbox.processor.dto.EmailDTO;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/ingest")
@ConditionalOnProperty(name = "smart.http.ingest.enabled", havingValue = "true")
public class IngestionController {

    private static final Logger logger = LoggerFactory.getLogger(IngestionController.class);

    private final AiService aiService;
    private final MailSummaryRepository mailSummaryRepository;

    public IngestionController(AiService aiService, MailSummaryRepository mailSummaryRepository) {
        this.aiService = aiService;
        this.mailSummaryRepository = mailSummaryRepository;
    }

    @PostMapping("/email")
    public String ingestEmail(@RequestBody EmailDTO emailDTO) {
        logger.info("HTTP ingest enabled: source={} externalId={} subject={}",
                emailDTO.getSource(), emailDTO.getExternalId(), emailDTO.getSubject());

        try {
            String source = normSource(emailDTO.getSource());
            String externalId = requireExternalIdOrFallback(emailDTO);

            // ✅ DB 幂等：存在就跳过（或唯一约束兜底）
            if (mailSummaryRepository.existsBySourceAndExternalId(source, externalId)) {
                enrichExisting(emailDTO, source, externalId);
                logger.info("HTTP duplicate ignored. source={} externalId={}", source, externalId);
                return "Duplicate Ignored";
            }

            MailSummary summary = aiService.processEmail(
                    emailDTO.getSender(),
                    emailDTO.getSubject(),
                    emailDTO.getContent(),
                    source,
                    emailDTO.getTimestamp(),
                    externalId);

            summary.setHtmlContent(emailDTO.getHtmlContent());
            try {
                mailSummaryRepository.save(summary);
            } catch (DataIntegrityViolationException dup) {
                logger.info("HTTP duplicate caught by DB unique constraint. source={} externalId={}", source,
                        externalId);
                return "Duplicate Ignored";
            }

            return "Success";
        } catch (Exception e) {
            logger.error("Failed to process HTTP ingestion", e);
            return "Failed: " + e.getMessage();
        }
    }

    private String requireExternalIdOrFallback(EmailDTO dto) {
        if (dto.getExternalId() != null && !dto.getExternalId().isBlank()) {
            return dto.getExternalId().trim();
        }
        // fallback（不推荐，但保证不为空）
        String raw = normSource(dto.getSource()) + "|" + norm(dto.getSender()) + "|" + norm(dto.getSubject()) + "|" +
                (dto.getTimestamp() == null ? "null" : dto.getTimestamp()) + "|" +
                org.springframework.util.DigestUtils
                        .md5DigestAsHex(norm(dto.getContent()).getBytes(StandardCharsets.UTF_8));
        String hash = org.springframework.util.DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        logger.warn("externalId missing, using fallback hash. source={} subject={}", dto.getSource(), dto.getSubject());
        return hash;
    }

    private void enrichExisting(EmailDTO email, String source, String externalId) {
        mailSummaryRepository.findFirstBySourceAndExternalId(source, externalId).ifPresent(existing -> {
            boolean changed = false;
            if ((existing.getOriginalSubject() == null || existing.getOriginalSubject().isBlank())
                    && email.getSubject() != null && !email.getSubject().isBlank()) {
                existing.setOriginalSubject(email.getSubject());
                changed = true;
            }
            if ((existing.getContent() == null || existing.getContent().isBlank())
                    && email.getContent() != null && !email.getContent().isBlank()) {
                existing.setContent(email.getContent());
                changed = true;
            }
            if (email.getHtmlContent() != null && !email.getHtmlContent().isBlank()) {
                existing.setHtmlContent(email.getHtmlContent());
                existing.setContent(email.getContent());
                changed = true;
            }
            if (changed) {
                mailSummaryRepository.save(existing);
            }
        });
    }

    private String normSource(String s) {
        if (s == null)
            return "UNKNOWN";
        return s.trim();
    }

    private String norm(String s) {
        return s == null ? "" : s.trim();
    }
}

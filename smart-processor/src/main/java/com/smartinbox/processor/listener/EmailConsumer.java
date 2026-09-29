package com.smartinbox.processor.listener;

import com.smartinbox.processor.dto.EmailDTO;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "smart.mq.email.enabled", havingValue = "true")
@RocketMQMessageListener(topic = "EMAIL_RAW_TOPIC", consumerGroup = "email-processor-group-v4", consumeThreadMax = 20)
public class EmailConsumer implements RocketMQListener<EmailDTO> {

    private static final Logger logger = LoggerFactory.getLogger(EmailConsumer.class);

    private final AiService aiService;
    private final MailSummaryRepository mailSummaryRepository;
    private final StringRedisTemplate redisTemplate;

    public EmailConsumer(AiService aiService,
            MailSummaryRepository mailSummaryRepository,
            StringRedisTemplate redisTemplate) {
        this.aiService = aiService;
        this.mailSummaryRepository = mailSummaryRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onMessage(EmailDTO email) {
        String source = normSource(email.getSource());
        String externalId = requireExternalIdOrFallback(email);

        if (mailSummaryRepository.existsBySourceAndExternalId(source, externalId)) {
            enrichExisting(email, source, externalId);
            return;
        }
        // Retry a busy/abandoned lock; never acknowledge a message with no database row.
        String dedupKey = "smartinbox:processing:v2:" + source + ":" + externalId;
        String owner = java.util.UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(dedupKey, owner, 10, TimeUnit.MINUTES))) {
            throw new IllegalStateException("Message processing in progress; retry delivery");
        }

        try {
            // ✅ DB 幂等：存在就直接跳过（避免 AI 白跑）
            if (mailSummaryRepository.existsBySourceAndExternalId(source, externalId)) {
                enrichExisting(email, source, externalId);
                logger.info("Duplicate MQ ignored by DB exists. source={} externalId={}", source, externalId);
                return;
            }

            MailSummary result = aiService.processEmail(
                    email.getSender(),
                    email.getSubject(),
                    email.getContent(),
                    source,
                    email.getTimestamp(),
                    externalId);

            result.setHtmlContent(email.getHtmlContent());
            result.setBodySynced(true);
            try {
                mailSummaryRepository.save(result);
            } catch (DataIntegrityViolationException constraintFailure) {
                // A constraint failure can also mean invalid data (length, nullability, etc.).
                // Acknowledge only after verifying that this exact message was persisted elsewhere.
                if (mailSummaryRepository.existsBySourceAndExternalId(source, externalId)) {
                    logger.info("Duplicate MQ confirmed by DB key. source={} externalId={}", source, externalId);
                    return;
                }
                throw constraintFailure;
            }

            logger.info("Processed MQ | Status: {} | Source: {} | ExternalId: {} | Subject: {}",
                    result.getStatus(), result.getSource(), result.getExternalId(), result.getSubject());

        } catch (Exception e) {
            // 失败释放 Redis 锁，允许 RocketMQ 重投递后再处理

            logger.error("Error consuming MQ email: {}", email.getSubject(), e);
            throw e;
        } finally {
            redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                    "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class),
                    java.util.List.of(dedupKey), owner);
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
        logger.warn("externalId missing in MQ payload, using fallback hash. source={} subject={}", dto.getSource(),
                dto.getSubject());
        return hash;
    }

    private void enrichExisting(EmailDTO email, String source, String externalId) {
        // Update body columns only: an AI/collector merge must never overwrite local user state.
        mailSummaryRepository.enrichBody(source,externalId,email.getSubject(),email.getContent(),email.getHtmlContent(),java.time.LocalDateTime.now());
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

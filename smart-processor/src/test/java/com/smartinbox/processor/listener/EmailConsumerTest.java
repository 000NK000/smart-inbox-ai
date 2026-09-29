package com.smartinbox.processor.listener;
import com.smartinbox.processor.dto.EmailDTO;
import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.service.AiService;
import org.springframework.data.redis.core.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailConsumerTest {
    @Test void enrichmentPreservesLocalReadAndStarState() {
        var repo = mock(MailSummaryRepository.class);
        var ai = mock(AiService.class);
        var redis = mock(StringRedisTemplate.class);
        var existing = new MailSummary(); existing.setInboxRead(true); existing.setStarred(true);
        when(repo.existsBySourceAndExternalId("OUTLOOK", "id")).thenReturn(true);
        when(repo.findFirstBySourceAndExternalId("OUTLOOK", "id")).thenReturn(Optional.of(existing));
        var dto = new EmailDTO("test", "sender", "full text", 1L, "OUTLOOK", "id"); dto.setHtmlContent("<p>full HTML</p>");
        new EmailConsumer(ai, repo, redis).onMessage(dto);
        assertTrue(existing.isInboxRead()); assertTrue(existing.isStarred());
        verifyNoInteractions(ai, redis);
        verify(repo).enrichBody(eq("OUTLOOK"),eq("id"),eq("test"),eq("full text"),eq("<p>full HTML</p>"),any());
        verify(repo,never()).save(any());
    }
    @Test void inFlightMessageWithoutDatabaseRowMustRetry() {
        var repo = mock(MailSummaryRepository.class);
        var ai = mock(AiService.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> new EmailConsumer(ai, repo, redis).onMessage(new EmailDTO("test", "sender", "body", 1L, "OUTLOOK", "id")));
        verifyNoInteractions(ai);
    }
    @Test void confirmedConcurrentDuplicateCanBeAcknowledgedAndReleasesLock() {
        var repo = mock(MailSummaryRepository.class);
        var ai = mock(AiService.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(repo.existsBySourceAndExternalId("OUTLOOK", "id")).thenReturn(false, false, true);
        when(ai.processEmail(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(new MailSummary());
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("Synthetic unique-key race"));
        assertDoesNotThrow(() -> new EmailConsumer(ai, repo, redis).onMessage(new EmailDTO("test", "sender", "body", 1L, "OUTLOOK", "id")));
        verify(repo, times(3)).existsBySourceAndExternalId("OUTLOOK", "id");
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.List.of("smartinbox:processing:v2:OUTLOOK:id")), anyString());
    }
    @Test void unrelatedIntegrityFailureMustEscapeForMqRetryAndReleaseLock() {
        var repo = mock(MailSummaryRepository.class);
        var ai = mock(AiService.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(repo.existsBySourceAndExternalId("OUTLOOK", "id")).thenReturn(false);
        when(ai.processEmail(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(new MailSummary());
        var failure = new DataIntegrityViolationException("Synthetic required column missing");
        when(repo.save(any())).thenThrow(failure);
        var thrown = assertThrows(DataIntegrityViolationException.class,
                () -> new EmailConsumer(ai, repo, redis).onMessage(new EmailDTO("test", "sender", "body", 1L, "OUTLOOK", "id")));
        assertSame(failure, thrown);
        verify(repo, times(3)).existsBySourceAndExternalId("OUTLOOK", "id");
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(java.util.List.of("smartinbox:processing:v2:OUTLOOK:id")), anyString());
    }
}

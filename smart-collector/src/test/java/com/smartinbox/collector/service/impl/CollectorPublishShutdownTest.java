package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.dto.EmailDTO;
import com.smartinbox.collector.runtime.CollectorShutdownSignal;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectorPublishShutdownTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void stopBeforePublishDoesNotSendOrCheckpointDesktopMail() {
        var mq = mock(RocketMQTemplate.class);
        var service = new OutlookDesktopService(mock(CredentialVaultService.class), mq, mapper);
        var signal = new CollectorShutdownSignal();
        var file = directory.resolve("stopped.txt");
        ReflectionTestUtils.setField(service, "shutdown", signal);
        ReflectionTestUtils.setField(service, "processedFile", file);
        signal.stop();
        assertThrows(CancellationException.class, () -> service.publishMessages(mapper.createArrayNode().add(desktop("first"))));
        verifyNoInteractions(mq);
        assertFalse(Files.exists(file));
    }

    @Test void stopDuringDesktopSendCommitsItsCheckpointButDoesNotSendTheNextMail() throws Exception {
        var mq = mock(RocketMQTemplate.class);
        var service = new OutlookDesktopService(mock(CredentialVaultService.class), mq, mapper);
        var signal = new CollectorShutdownSignal();
        var file = directory.resolve("desktop.txt");
        ReflectionTestUtils.setField(service, "shutdown", signal);
        ReflectionTestUtils.setField(service, "processedFile", file);
        doAnswer(call -> { signal.stop(); assertFalse(Thread.currentThread().isInterrupted()); return null; })
                .when(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        var first = desktop("first"); var second = desktop("second");
        assertThrows(CancellationException.class, () -> service.publishMessages(mapper.createArrayNode().add(first).add(second)));
        verify(mq, times(1)).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        String persisted = Files.readString(file);
        assertTrue(persisted.contains(OutlookDesktopService.externalId(first)));
        assertFalse(persisted.contains(OutlookDesktopService.externalId(second)));
    }

    @Test void stopDuringGraphSendCommitsItsCheckpointButDoesNotSendTheNextMail() throws Exception {
        var mq = mock(RocketMQTemplate.class);
        var service = new OutlookGraphService(mock(OutlookOAuthService.class), mq, mapper);
        var signal = new CollectorShutdownSignal();
        var file = directory.resolve("graph.txt");
        ReflectionTestUtils.setField(service, "shutdown", signal);
        ReflectionTestUtils.setField(service, "processedFile", file);
        doAnswer(call -> { signal.stop(); assertFalse(Thread.currentThread().isInterrupted()); return null; })
                .when(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        var first = graph("first"); var second = graph("second");
        assertThrows(CancellationException.class, () -> service.publishMessages(mapper.createArrayNode().add(first).add(second)));
        verify(mq, times(1)).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        String persisted = Files.readString(file);
        assertTrue(persisted.contains(OutlookGraphService.externalId(first)));
        assertFalse(persisted.contains(OutlookGraphService.externalId(second)));
    }

    private com.fasterxml.jackson.databind.JsonNode desktop(String id) {
        return mapper.createObjectNode().put("entryId", id).put("internetMessageId", "<" + id + ">")
                .put("receivedTime", Instant.now().toString()).put("body", "Fixture mail");
    }
    private com.fasterxml.jackson.databind.JsonNode graph(String id) {
        var result = mapper.createObjectNode().put("id", id).put("internetMessageId", "<" + id + ">")
                .put("receivedDateTime", Instant.now().toString());
        result.putObject("body").put("content", "Fixture mail");
        return result;
    }
}

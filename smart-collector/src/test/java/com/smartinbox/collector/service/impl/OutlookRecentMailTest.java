package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.dto.EmailDTO;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutlookRecentMailTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void desktopPublishesReadAndUnreadMessagesOnlyOnce() throws Exception {
        var mq=mock(RocketMQTemplate.class);
        var desktop=new OutlookDesktopService(mock(CredentialVaultService.class),mq,mapper);
        ReflectionTestUtils.setField(desktop,"processedFile",directory.resolve("desktop.txt"));
        var messages=mapper.createArrayNode();
        for(int i=0;i<2;i++) messages.addObject().put("internetMessageId","<desktop-"+i+">")
                .put("unread",i==0).put("receivedTime",Instant.now().toString()).put("subject","Example")
                .put("body","Read and unread content").put("htmlBody","<p>Content</p>");
        assertEquals(2,desktop.publishMessages(messages));
        assertEquals(0,desktop.publishMessages(messages));
        verify(mq,times(2)).convertAndSend(eq("EMAIL_RAW_TOPIC"),any(EmailDTO.class));
        assertFalse(messages.get(1).path("unread").asBoolean());
    }
    @Test void graphPublishesReadAndUnreadAcrossPagesWithoutDuplicates() throws Exception {
        var mq=mock(RocketMQTemplate.class);
        var graph=new OutlookGraphService(mock(OutlookOAuthService.class),mq,mapper);
        ReflectionTestUtils.setField(graph,"processedFile",directory.resolve("graph.txt"));
        var read=mapper.createObjectNode().put("id","read-message").put("isRead",true)
                .put("receivedDateTime",Instant.now().toString()).put("subject","Read upstream");
        read.putObject("body").put("contentType","html").put("content","<p>Full mail</p>");
        var unread=read.deepCopy().put("id","unread-message").put("isRead",false);
        assertEquals(1,graph.publishMessages(mapper.createArrayNode().add(read)));
        assertEquals(1,graph.publishMessages(mapper.createArrayNode().add(read).add(unread)));
        verify(mq,times(2)).convertAndSend(eq("EMAIL_RAW_TOPIC"),any(EmailDTO.class));
        assertTrue(read.path("isRead").asBoolean());
    }
}

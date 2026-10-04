package com.smartinbox.collector.service.impl;

import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.dto.EmailDTO;
import jakarta.mail.*;
import jakarta.mail.search.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImapRecentMailTest {
    @TempDir Path directory;
    final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    final CredentialVaultService vault = mock(CredentialVaultService.class);
    final Session session = mock(Session.class);
    final Store store = mock(Store.class);
    final Folder inbox = mock(Folder.class);
    ImapEmailService service;
    @BeforeEach void setup() throws Exception {
        service = new ImapEmailService(mq, vault);
        when(vault.value(anyString(), nullable(String.class))).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "host", "imap.example.test");
        ReflectionTestUtils.setField(service, "username", "user@example.test");
        ReflectionTestUtils.setField(service, "password", "test-only");
        ReflectionTestUtils.setField(service, "protocol", "imap");
        ReflectionTestUtils.setField(service, "processedFilePath", directory.resolve("processed.txt"));
        when(session.getStore("imap")).thenReturn(store);
        when(store.getFolder("INBOX")).thenReturn(inbox);
    }
    Message message(int id, boolean seen, int hoursAgo) throws Exception {
        Message mail = mock(Message.class);
        when(mail.getReceivedDate()).thenReturn(new Date(System.currentTimeMillis() - hoursAgo * 3600000L));
        when(mail.getSubject()).thenReturn("Test mail " + id);
        when(mail.getHeader("Message-ID")).thenReturn(new String[]{"<" + id + "@example.test>"});
        when(mail.isSet(Flags.Flag.SEEN)).thenReturn(seen);
        when(mail.isMimeType("text/plain")).thenReturn(true);
        when(mail.getContent()).thenReturn("Fixture body");
        return mail;
    }
    void collect() {
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            service.fetchRecentEmails();
        }
    }
    @Test void shutdownDuringMetadataReadSkipsBodyAndDoesNotMarkOrPublishMail() throws Exception {
        var signal = new com.smartinbox.collector.runtime.CollectorShutdownSignal();
        ReflectionTestUtils.setField(service, "shutdown", signal);
        var mail = message(1, true, 1);
        when(mail.getSubject()).thenAnswer(call -> { signal.stop(); return "Test mail 1"; });
        when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[]{mail});
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            var report = service.fetchSource("GMAIL");
            assertEquals("collector_stopping", report.failureCode());
        }
        verifyNoInteractions(mq);
        verify(mail, never()).getContent(); verify(mail, never()).setFlag(any(), anyBoolean());
        assertFalse(java.nio.file.Files.exists(directory.resolve("processed.txt.GMAIL")));
    }
    @Test void shutdownDuringImapSendFinishesCheckpointWithoutPublishingTheNextMail() throws Exception {
        var signal = new com.smartinbox.collector.runtime.CollectorShutdownSignal();
        ReflectionTestUtils.setField(service, "shutdown", signal);
        Message[] messages = {message(1, false, 1), message(2, false, 2)};
        when(inbox.search(any(SearchTerm.class))).thenReturn(messages);
        doAnswer(call -> { signal.stop(); assertFalse(Thread.currentThread().isInterrupted()); return null; })
                .when(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            assertThrows(java.util.concurrent.CancellationException.class, () -> service.fetchSource("GMAIL"));
        }
        var dto = org.mockito.ArgumentCaptor.forClass(EmailDTO.class);
        verify(mq, times(1)).convertAndSend(eq("EMAIL_RAW_TOPIC"), dto.capture());
        assertEquals("Test mail 1", dto.getValue().getSubject());
        assertTrue(java.nio.file.Files.readString(directory.resolve("processed.txt.GMAIL")).contains(dto.getValue().getExternalId()));
        assertTrue(java.nio.file.Files.readString(directory.resolve("processed.txt")).contains(dto.getValue().getExternalId()));
    }
    @Test void includesSourceReadMailBeyondTwentyAndDeduplicatesWithoutWritingFlags() throws Exception {
        Message[] messages = new Message[26];
        for(int i=0;i<25;i++) messages[i]=message(i, i%2==0, 119);
        messages[25]=message(25,false,121);
        when(inbox.search(any(SearchTerm.class))).thenReturn(messages);
        collect();
        verify(mq,times(25)).convertAndSend(eq("EMAIL_RAW_TOPIC"),any(EmailDTO.class));
        var term = org.mockito.ArgumentCaptor.forClass(SearchTerm.class);
        verify(inbox).search(term.capture());
        assertInstanceOf(ReceivedDateTerm.class,term.getValue());
        verify(inbox).open(Folder.READ_ONLY);
        collect();
        verify(mq,times(25)).convertAndSend(eq("EMAIL_RAW_TOPIC"),any(EmailDTO.class));
        for(Message message:messages) verify(message,never()).setFlag(any(),anyBoolean());
    }
    @Test void dateSearchFallbackStillIncludesReadMailAndHonorsCutoff() throws Exception {
        when(inbox.search(any(SearchTerm.class))).thenThrow(new MessagingException("Date search unsupported"));
        Message[] messages={message(1,true,100),message(2,false,121)};
        when(inbox.getMessages()).thenReturn(messages);
        collect();
        verify(inbox).getMessages();
        var dto=org.mockito.ArgumentCaptor.forClass(EmailDTO.class);
        verify(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"),dto.capture());
        assertEquals("Test mail 1",dto.getValue().getSubject());
    }
}

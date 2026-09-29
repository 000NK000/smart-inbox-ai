package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.*;
import com.smartinbox.collector.credentials.CredentialVaultService;
import com.smartinbox.collector.dto.EmailDTO;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutlookIncrementalTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    JsonNode metadata(String id) { return mapper.createObjectNode().put("id", id).put("internetMessageId", "<" + id + ">").put("receivedDateTime", Instant.now().toString()); }
    JsonNode detail(String id) {
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) metadata(id);
        result.put("subject", "Fixture").put("isRead", true);
        result.putObject("body").put("contentType", "text").put("content", "Complete fixture body"); return result;
    }
    class Graph extends OutlookGraphService {
        final List<String> requests = new ArrayList<>();
        final Map<String, JsonNode> details = new HashMap<>();
        JsonNode first, second;
        Graph(OutlookOAuthService oauth, RocketMQTemplate mq) { super(oauth, mq, mapper); }
        @Override JsonNode readGraph(String url, String token) {
            requests.add(url);
            if (url.contains("/me/messages/")) return details.get(url.substring(url.indexOf("/me/messages/") + 13, url.indexOf('?')));
            return url.contains("skiptoken=next") ? second : first;
        }
    }
    @Test void graphPagesOnlyMetadataAndDownloadsNewOrMissingBodyIncludingReadMail() throws Exception {
        var oauth = mock(OutlookOAuthService.class); when(oauth.accessToken()).thenReturn(Optional.of("test-token"));
        var mq = mock(RocketMQTemplate.class); var graph = new Graph(oauth, mq);
        ReflectionTestUtils.setField(graph, "processedFile", directory.resolve("graph.txt"));
        var index = mock(MailSyncIndex.class);
        when(index.snapshot("OUTLOOK")).thenReturn(new MailSyncIndex.Snapshot(true, Set.of(OutlookGraphService.externalId(metadata("known")))));
        ReflectionTestUtils.setField(graph, "syncIndex", index);
        graph.first = mapper.createObjectNode().set("value", mapper.createArrayNode().add(metadata("known")).add(metadata("new")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) graph.first).put("@odata.nextLink", "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?skiptoken=next");
        graph.second = mapper.createObjectNode().set("value", mapper.createArrayNode().add(metadata("backfill")));
        graph.details.put("new", detail("new")); graph.details.put("backfill", detail("backfill"));
        // A legacy sent checkpoint without durable storage must not suppress replay.
        Files.writeString(directory.resolve("graph.txt"), "html-v1:" + OutlookGraphService.externalId(metadata("backfill")));
        assertTrue(graph.fetchRecentEmails());
        assertEquals(3, graph.report().scanned()); assertEquals(2, graph.report().bodyFetched()); assertEquals(1, graph.report().skipped());
        assertTrue(graph.requests.get(0).contains("$select=id,internetMessageId,receivedDateTime"));
        assertFalse(graph.requests.get(0).contains(",body"));
        assertFalse(graph.requests.stream().anyMatch(url -> url.contains("/messages/known?")));
        verify(mq, times(2)).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        graph.requests.clear(); assertTrue(graph.fetchRecentEmails());
        assertEquals(0, graph.report().bodyFetched());
        assertEquals(2, graph.requests.size(), "Both metadata pages still discover newly arriving mail");
    }
    @Test void failedPublishDoesNotSuppressNextAttemptAndForeignContinuationIsRejected() throws Exception {
        var oauth = mock(OutlookOAuthService.class); when(oauth.accessToken()).thenReturn(Optional.of("test-token"));
        var mq = mock(RocketMQTemplate.class); var graph = new Graph(oauth, mq);
        ReflectionTestUtils.setField(graph, "processedFile", directory.resolve("failed.txt"));
        graph.first = mapper.createObjectNode().set("value", mapper.createArrayNode().add(metadata("new")));
        graph.details.put("new", detail("new"));
        doThrow(new IllegalStateException("fixture broker failure")).doNothing().when(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        assertFalse(graph.fetchRecentEmails()); assertTrue(graph.fetchRecentEmails());
        verify(mq, times(2)).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
        ((com.fasterxml.jackson.databind.node.ObjectNode) graph.first).put("@odata.nextLink", "https://example.test/token");
        assertFalse(graph.fetchRecentEmails());
        assertFalse(graph.requests.stream().anyMatch(url -> url.startsWith("https://example.test")));
    }
    class Desktop extends OutlookDesktopService {
        JsonNode metadata, bodies; List<String> requested; int metadataCalls, bodyCalls;
        Desktop(RocketMQTemplate mq) { super(mock(CredentialVaultService.class), mq, mapper); }
        @Override ScriptResult runBridge(boolean metadataOnly, List<String> ids, Instant cutoff) {
            if (metadataOnly) { metadataCalls++; return new ScriptResult("connected", metadata); }
            bodyCalls++; requested = ids; return new ScriptResult("connected", bodies);
        }
    }
    JsonNode desktopMeta(String id) { return mapper.createObjectNode().put("entryId", id).put("internetMessageId", "<" + id + ">").put("receivedTime", Instant.now().toString()); }
    @Test void desktopRequestsOnlySelectedIdentitiesAndRetainsPendingAcrossRestart() throws Exception {
        var mq = mock(RocketMQTemplate.class); var desktop = new Desktop(mq);
        Path file = directory.resolve("desktop.txt"); ReflectionTestUtils.setField(desktop, "processedFile", file);
        var index = mock(MailSyncIndex.class);
        when(index.snapshot("OUTLOOK")).thenReturn(new MailSyncIndex.Snapshot(true, Set.of(OutlookDesktopService.externalId(desktopMeta("known")))));
        ReflectionTestUtils.setField(desktop, "syncIndex", index);
        desktop.metadata = mapper.createArrayNode().add(desktopMeta("known")).add(desktopMeta("new"));
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) desktopMeta("new"); body.put("body", "Full fixture").put("unread", false);
        desktop.bodies = mapper.createArrayNode().add(body);
        assertTrue(desktop.fetchRecentEmails()); assertEquals(List.of("new"), desktop.requested);
        var restarted = new Desktop(mq); restarted.metadata = desktop.metadata; restarted.bodies = desktop.bodies;
        ReflectionTestUtils.setField(restarted, "processedFile", file); ReflectionTestUtils.setField(restarted, "syncIndex", index);
        assertTrue(restarted.fetchRecentEmails()); assertEquals(0, restarted.bodyCalls); assertEquals(2, restarted.report().skipped());
        verify(mq).convertAndSend(eq("EMAIL_RAW_TOPIC"), any(EmailDTO.class));
    }
}

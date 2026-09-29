package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailSummaryAiTest {
    @Test void validatesBeforeCachingKeysIncludeSubjectModelAndIgnoredMailKeepsReceivedTime() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var stored = new ConcurrentHashMap<String, String>();
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), eq(7L), eq(TimeUnit.DAYS));
        var reply = new AtomicReference<>("{\"action\":\"PROCESS\",\"category\":\"Work\",\"summary\":\"Submit homework\",\"urgency\":4,\"senderName\":\"Teacher\",\"simplifiedTitle\":\"Homework\"}");
        var request = new AtomicReference<String>(); var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var envelope = mapper.createObjectNode(); envelope.putArray("choices").addObject().putObject("message").put("content", reply.get());
            byte[] data = envelope.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        var metrics = new SimpleMeterRegistry();
        try (var scheduler = new AiRequestScheduler(1, 4)) {
            var ai = new AiService(mapper, metrics, redis, scheduler);
            ReflectionTestUtils.setField(ai, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(ai, "ollamaModel", "model-a");
            long timestamp = Instant.parse("2026-09-19T12:00:00Z").toEpochMilli();
            ai.processEmail("Teacher", "Subject A", "Same body", "GMAIL", timestamp);
            ai.processEmail("Teacher", "Subject A", "Same body", "GMAIL", timestamp);
            assertEquals(1, calls.get()); assertEquals(1, stored.size());
            assertEquals(800, mapper.readTree(request.get()).path("max_tokens").asInt());
            ai.processEmail("Teacher", "Subject B", "Same body", "GMAIL", timestamp);
            ReflectionTestUtils.setField(ai, "ollamaModel", "model-b");
            ai.processEmail("Teacher", "Subject B", "Same body", "GMAIL", timestamp);
            assertEquals(3, calls.get()); assertEquals(3, stored.size());
            reply.set("{\"category\":\"invalid\"}");
            ai.processEmail("Teacher", "Broken", "Same body", "GMAIL", timestamp);
            assertEquals(3, stored.size(), "Invalid analysis must not enter the cache");
            reply.set("{\"category\":\"Ad\",\"action\":\"IGNORE\"}");
            var ignored = ai.processEmail("Shop", "Offer", "Promo", "GMAIL", timestamp);
            assertEquals("IGNORE", ignored.getAction());
            assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()), ignored.getCreatedTime());
            var cached = ai.processEmail("Shop", "Offer", "Promo", "GMAIL", timestamp);
            assertEquals(ignored.getCreatedTime(), cached.getCreatedTime());
        } finally { metrics.close(); server.stop(0); }
    }
}

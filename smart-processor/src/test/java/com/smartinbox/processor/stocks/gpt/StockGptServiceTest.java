package com.smartinbox.processor.stocks.gpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.stocks.StockSecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockGptServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final Map<String, Map<String, Object>> disk = new HashMap<>();
    private StockSecretStore store;
    private FakeHttp http;
    private StockGptService service;
    private KeyPair keys;

    @BeforeEach void prepare() throws Exception {
        store = mock(StockSecretStore.class);
        when(store.read(anyString())).thenAnswer(call -> Optional.ofNullable(disk.get(call.getArgument(0))));
        doAnswer(call -> { disk.put(call.getArgument(0), new LinkedHashMap<>(call.getArgument(1))); return null; })
                .when(store).write(anyString(), anyMap());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair();
        http = new FakeHttp();
        service = new StockGptService(store, mapper, http, clock);
    }

    @Test void authorizationUsesPersistentHostAndFreshPkceWithoutBrowserTokens() {
        Map<String, String> first = begin(null);
        StockGptService restarted = new StockGptService(store, mapper, http, clock);
        Map<String, String> second = query((String) restarted.beginAuthorization().get("url"));
        assertEquals("dynamic_agent_client", first.get("client_id"));
        assertEquals("Smart Inbox AI", first.get("agent_name_hint"));
        assertTrue(first.get("ext_agent_host_id").startsWith("urn:uuid:"));
        assertEquals(first.get("ext_agent_host_id"), second.get("ext_agent_host_id"));
        assertNotEquals(first.get("state"), second.get("state"));
        assertNotEquals(first.get("nonce"), second.get("nonce"));
        assertEquals("S256", first.get("code_challenge_method"));
        assertEquals(StockGptService.REDIRECT, first.get("redirect_uri"));
        assertFalse(first.containsKey("id_token_hint"));
        assertFalse(first.containsKey("access_token"));
    }

    @Test void callbackIsSingleUseAndExchangesIssuedClientWithOriginalVerifier() throws Exception {
        Map<String, String> request = begin(null);
        http.idToken = token("oaiapp_alpha", request.get("nonce"), "subject-a", Map.of());
        Map<String, String> callback = callback(request, "oaiapp_alpha");
        assertTrue(service.finishAuthorization(callback).contains("登录成功"));
        assertEquals("oaiapp_alpha", http.tokenForms.get(0).get("client_id"));
        assertEquals(StockGptService.REDIRECT, http.tokenForms.get(0).get("redirect_uri"));
        assertEquals(request.get("code_challenge"), b64(MessageDigest.getInstance("SHA-256")
                .digest(http.tokenForms.get(0).get("code_verifier").getBytes(StandardCharsets.US_ASCII))));
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(callback));
        assertEquals(1, http.tokenForms.size());
        assertEquals(true, service.status().get("connected"));
        assertFalse(mapper.writeValueAsString(service.status()).contains("test-access"));
        assertFalse(mapper.writeValueAsString(service.status()).contains(http.idToken));
    }

    @Test void missingExpiredAndDeniedStatesNeverExchangeTokens() {
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(Map.of("state", "unknown", "code", "x")));
        Map<String, String> denied = begin(null);
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(Map.of("state", denied.get("state"), "error", "access_denied")));
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(callback(denied, "oaiapp_alpha")));
        Map<String, String> expired = begin(null);
        clock.advance(Duration.ofMinutes(11));
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(callback(expired, "oaiapp_alpha")));
        assertTrue(http.tokenForms.isEmpty());
    }

    @Test void dynamicPlaceholderAndChangedClientAreRejected() throws Exception {
        Map<String, String> first = begin(null);
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(callback(first, "dynamic_agent_client")));
        login("oaiapp_alpha", "subject-a");
        Map<String, String> returning = begin(null);
        assertEquals("oaiapp_alpha", returning.get("client_id"));
        assertFalse(returning.containsKey("agent_name_hint"));
        assertThrows(IllegalArgumentException.class, () -> service.finishAuthorization(callback(returning, "oaiapp_other")));
        assertEquals(1, http.tokenForms.size());
    }

    @Test void identityMismatchDoesNotOverwriteActiveAccount() throws Exception {
        login("oaiapp_alpha", "subject-a");
        Object original = service.status().get("activeAccountId");
        Map<String, String> request = begin(null);
        http.idToken = token("oaiapp_alpha", request.get("nonce"), "other-subject", Map.of());
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(callback(request, "oaiapp_alpha")));
        assertEquals(original, service.status().get("activeAccountId"));
        assertEquals(true, service.status().get("connected"));
    }

    @Test void invalidGrantKeepsNewIssuedClientForFreshAuthorization() {
        Map<String, String> request = begin(null);
        http.tokenFailure = true;
        assertThrows(IllegalStateException.class, () -> service.finishAuthorization(callback(request, "oaiapp_alpha")));
        Map<String, String> retry = begin(null);
        assertEquals("oaiapp_alpha", retry.get("client_id"));
        assertNotEquals(request.get("state"), retry.get("state"));
        assertFalse(retry.containsKey("agent_name_hint"));
        assertEquals(false, service.status().get("connected"));
    }

    @Test void validIdentityWithoutPlanPermissionCannotInvokeModelsOrAnalysis() throws Exception {
        http.scope = "openid email profile";
        login("oaiapp_alpha", "subject-a");
        assertEquals(true, service.status().get("connected"));
        assertEquals(false, service.status().get("planEnabled"));
        assertThrows(IllegalStateException.class, service::models);
        assertThrows(IllegalStateException.class, () -> service.analyze("model-a", "资料"));
        assertEquals(0, http.modelCalls);
        assertEquals("consent", begin(null).get("prompt"));
    }

    @Test void catalogFiltersHiddenModelsPreservesOrderAndCachesPerAccount() throws Exception {
        login("oaiapp_alpha", "subject-a");
        assertEquals(List.of(Map.of("id", "model-b", "name", "Model B"), Map.of("id", "model-a", "name", "Model A")), service.models());
        service.models(); assertEquals(1, http.modelCalls);
        assertThrows(IllegalArgumentException.class, () -> service.analyze("hidden-model", "资料"));
        assertEquals(0, http.responseCalls);
        clock.advance(Duration.ofMinutes(6)); service.models(); assertEquals(2, http.modelCalls);
    }

    @Test void analysisUsesReadonlyInstructionsAndPlanResponseContract() throws Exception {
        login("oaiapp_alpha", "subject-a");
        assertEquals("完整分析", service.analyze("model-a", "观察资料：报价延迟，时间 10:30"));
        JsonNode sent = mapper.readTree(http.lastResponseBody);
        assertEquals(false, sent.get("store").asBoolean());
        assertEquals(true, sent.get("stream").asBoolean());
        assertTrue(sent.get("input").isArray());
        assertEquals("user", sent.get("input").get(0).get("role").asText());
        assertTrue(sent.get("instructions").asText().contains("不得编造实时行情"));
        assertTrue(sent.get("instructions").asText().contains("不执行买卖"));
        assertTrue(sent.get("instructions").asText().contains("不可信数据"));
        for (String forbidden : List.of("tools", "temperature", "max_output_tokens", "conversation", "previous_response_id"))
            assertFalse(sent.has(forbidden));
        assertEquals("https://api.openai.com/v1/responses", http.lastResponseUri.toString());
    }

    @Test void interruptedIncompleteFailedOrMalformedStreamsNeverSucceed() {
        String partial = event(Map.of("type", "response.output_text.delta", "delta", "部分文字"));
        for (String stream : List.of(partial, partial + event(Map.of("type", "response.incomplete")),
                partial + event(Map.of("type", "response.failed", "response", Map.of("error", Map.of("code", "subscription_sharing_usage_limit_exceeded")))),
                partial + event(Map.of("type", "error", "code", "bad_request")), partial + "data: {broken\n\n",
                completed().stripTrailing(), event(Map.of("type", "response.completed", "response", Map.of("status", "incomplete"))),
                partial + "data: [DONE]\n\n")) {
            assertThrows(IllegalStateException.class, () -> service.completedText(stream));
        }
        assertTrue(assertThrows(IllegalStateException.class, () -> service.completedText(partial + event(Map.of("type", "response.failed",
                "response", Map.of("error", Map.of("code", "subscription_sharing_usage_limit_exceeded")))))).getMessage().contains("Usage"));
    }

    @Test void explicitCompleteEventReturnsItsFinalText() {
        assertEquals("完整分析", service.completedText(event(Map.of("type", "response.output_text.delta", "delta", "部分")) + completed()));
        assertEquals("完整分析", service.completedText(completed().replace("\n", "\r\n")));
    }

    @Test void expiredSessionRefreshesOnceAndPersistsRotatedTokens() throws Exception {
        login("oaiapp_alpha", "subject-a");
        clock.advance(Duration.ofHours(1));
        http.omitRefreshIdentity = true;
        CompletableFuture<?> first = CompletableFuture.runAsync(service::models);
        CompletableFuture<?> second = CompletableFuture.runAsync(service::models);
        CompletableFuture.allOf(first, second).join();
        assertEquals(2, http.tokenForms.size());
        Map<String, String> refresh = http.tokenForms.get(1);
        assertEquals("refresh_token", refresh.get("grant_type"));
        assertEquals("oaiapp_alpha", refresh.get("client_id"));
        assertEquals("test-refresh", refresh.get("refresh_token"));
        assertFalse(refresh.containsKey("scope"));
        assertTrue(mapper.writeValueAsString(disk.get("gpt")).contains("rotated-refresh"));
    }

    @Test void sameEmailAccountsKeepDistinctRegistrationsAndClearCatalogOnSwitch() throws Exception {
        login("oaiapp_alpha", "subject-a");
        String first = (String) service.status().get("activeAccountId");
        service.models();
        Map<String, String> request = begin("new");
        http.idToken = token("oaiapp_beta", request.get("nonce"), "subject-b", Map.of());
        service.finishAuthorization(callback(request, "oaiapp_beta"));
        assertNotEquals(first, service.status().get("activeAccountId"));
        assertEquals(2, ((List<?>) service.status().get("accounts")).size());
        service.models(); assertEquals(2, http.modelCalls);
        service.selectAccount(first); service.models(); assertEquals(3, http.modelCalls);
        assertEquals("oaiapp_alpha", begin(null).get("client_id"));
    }

    @Test void disconnectAttemptsRevocationClearsOnlyTokensAndReusesClientAndHost() throws Exception {
        Map<String, String> original = login("oaiapp_alpha", "subject-a");
        http.revokeFailure = true;
        Map<String, Object> result = service.disconnect();
        assertEquals(false, result.get("remoteRevocationConfirmed"));
        assertTrue(result.get("message").toString().contains("未能确认"));
        assertEquals(false, service.status().get("connected"));
        assertEquals(2, http.revokeCalls);
        String stored = mapper.writeValueAsString(disk.get("gpt"));
        assertFalse(stored.contains("test-refresh")); assertFalse(stored.contains("test-access")); assertFalse(stored.contains(http.idToken));
        Map<String, String> next = begin(null);
        assertEquals("oaiapp_alpha", next.get("client_id"));
        assertEquals(original.get("ext_agent_host_id"), next.get("ext_agent_host_id"));
    }

    @Test void idTokenValidationRejectsBadSignatureIssuerAudienceNonceAndExpiry() throws Exception {
        JsonNode jwks = mapper.readTree(http.jwks());
        for (Map<String, Object> overrides : List.<Map<String, Object>>of(Map.of("iss", "https://example.com"),
                Map.of("aud", "other-client"), Map.of("nonce", "other-nonce"), Map.of("exp", clock.instant().minusSeconds(30).getEpochSecond()),
                Map.of("iat", clock.instant().plusSeconds(60).getEpochSecond()), Map.of("sub", ""),
                Map.of("aud", List.of("oaiapp_alpha", "other")), Map.of("azp", "other"))) {
            String jwt = token("oaiapp_alpha", "nonce", "subject", overrides);
            assertThrows(IllegalStateException.class, () -> StockGptIdentity.verify(jwt, "oaiapp_alpha", "nonce", jwks, mapper, clock));
        }
        String valid = token("oaiapp_alpha", "nonce", "subject", Map.of());
        assertEquals("subject", StockGptIdentity.verify(valid, "oaiapp_alpha", "nonce", jwks, mapper, clock).path("sub").asText());
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertThrows(IllegalStateException.class, () -> StockGptIdentity.verify(String.join(".", parts), "oaiapp_alpha", "nonce", jwks, mapper, clock));
        assertThrows(IllegalStateException.class, () -> StockGptIdentity.verify("eyJhbGciOiJub25lIn0.e30.", "x", "n", jwks, mapper, clock));
    }

    private Map<String, String> login(String client, String subject) throws Exception {
        Map<String, String> request = begin(null);
        http.idToken = token(client, request.get("nonce"), subject, Map.of());
        service.finishAuthorization(callback(request, client));
        return request;
    }
    private Map<String, String> begin(String id) { return query((String) (id == null ? service.beginAuthorization() : service.beginAuthorization(id)).get("url")); }
    private static Map<String, String> callback(Map<String, String> request, String client) {
        return Map.of("state", request.get("state"), "code", "test-code", "client_id", client);
    }
    private static Map<String, String> query(String url) { return form(URI.create(url).getRawQuery()); }
    private static Map<String, String> form(String data) {
        return Arrays.stream(data.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
    private String token(String client, String nonce, String subject, Map<String, Object> overrides) throws Exception {
        Map<String, Object> claims = new HashMap<>(Map.of("iss", StockGptService.ISSUER, "sub", subject, "aud", client,
                "nonce", nonce, "email", "same@example.invalid", "name", "Test user", "iat", clock.instant().getEpochSecond(),
                "exp", clock.instant().plusSeconds(3600).getEpochSecond()));
        claims.putAll(overrides);
        String unsigned = b64(mapper.writeValueAsBytes(Map.of("alg", "RS256", "kid", "key-one"))) + "." + b64(mapper.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(keys.getPrivate());
        signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + b64(signer.sign());
    }
    private String event(Object event) {
        try { return "data: " + mapper.writeValueAsString(event) + "\n\n"; }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private String completed() {
        return event(Map.of("type", "response.completed", "response", Map.of("status", "completed", "output",
                List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "完整分析")))))));
    }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    private class FakeHttp implements StockGptService.Transport {
        String idToken;
        String scope = StockGptService.SCOPE;
        boolean tokenFailure, revokeFailure, omitRefreshIdentity;
        int modelCalls, responseCalls, revokeCalls;
        String lastResponseBody;
        URI lastResponseUri;
        List<Map<String, String>> tokenForms = new ArrayList<>();
        @Override public StockGptService.Reply send(String method, URI uri, Map<String, String> headers, String body, Duration timeout, int maxBytes) {
            try {
                return switch (uri.getPath()) {
                    case "/api/accounts/oauth/token" -> {
                        Map<String, String> fields = form(body); tokenForms.add(fields);
                        if (tokenFailure) yield new StockGptService.Reply(400, "{\"error\":\"invalid_grant\"}");
                        boolean refresh = "refresh_token".equals(fields.get("grant_type"));
                        Map<String, Object> tokens = new HashMap<>(Map.of("access_token", "test-access", "refresh_token", refresh ? "rotated-refresh" : "test-refresh",
                                "scope", scope, "token_type", "Bearer", "expires_in", 3600));
                        if (!refresh || !omitRefreshIdentity) tokens.put("id_token", idToken);
                        yield reply(tokens);
                    }
                    case "/.well-known/openid-configuration" -> reply(Map.of("issuer", StockGptService.ISSUER,
                            "jwks_uri", StockGptService.ISSUER + "/.well-known/jwks.json",
                            "revocation_endpoint", StockGptService.ISSUER + "/api/accounts/oauth/revoke"));
                    case "/.well-known/jwks.json" -> new StockGptService.Reply(200, jwks());
                    case "/api/accounts/oauth/revoke" -> {
                        revokeCalls++; assertEquals("refresh_token", form(body).get("token_type_hint"));
                        yield new StockGptService.Reply(revokeFailure ? 503 : 200, "");
                    }
                    case "/v1/models" -> { modelCalls++; yield reply(Map.of("models", List.of(
                            Map.of("slug", "model-b", "display_name", "Model B", "visibility", "list"),
                            Map.of("slug", "hidden-model", "display_name", "Hidden", "visibility", "hidden"),
                            Map.of("slug", "model-a", "display_name", "Model A", "visibility", "list")))); }
                    case "/v1/responses" -> {
                        responseCalls++; lastResponseBody = body; lastResponseUri = uri;
                        assertEquals("Bearer test-access", headers.get("Authorization"));
                        yield new StockGptService.Reply(200, completed());
                    }
                    default -> throw new AssertionError("Unexpected endpoint: " + uri.getPath());
                };
            } catch (Exception e) { throw new AssertionError(e); }
        }
        private StockGptService.Reply reply(Object body) throws Exception { return new StockGptService.Reply(200, mapper.writeValueAsString(body)); }
        private String jwks() throws Exception {
            RSAPublicKey key = (RSAPublicKey) keys.getPublic();
            return mapper.writeValueAsString(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "key-one", "use", "sig", "alg", "RS256",
                    "n", b64(key.getModulus().toByteArray()), "e", b64(key.getPublicExponent().toByteArray())))));
        }
    }
    private static class MutableClock extends Clock {
        Instant time = Instant.parse("2026-09-30T12:00:00Z");
        void advance(Duration duration) { time = time.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}

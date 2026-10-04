package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.stocks.StockSecretStore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent IBKR authorization; never reuses another application's consent or cookies. */
final class IbkrAuthorization {
    static final URI RESOURCE = URI.create("https://api.ibkr.com/v1/api/mcp-public");
    static final URI RESOURCE_METADATA = URI.create(RESOURCE + "/.well-known/oauth-protected-resource");
    static final URI SERVER_METADATA = URI.create("https://api.ibkr.com/.well-known/oauth-authorization-server");
    static final String TOKEN_KEY = "ibkr-oauth";
    static final String CLIENT_KEY = "ibkr-client";
    private final ObjectMapper mapper;
    private final StockSecretStore store;
    private final IbkrHttp http;
    private final Clock clock;
    private final String redirectUri;
    private final SecureRandom random = new SecureRandom();
    private Metadata metadata;
    private Pending pending;

    IbkrAuthorization(ObjectMapper mapper, StockSecretStore store, IbkrHttp http, Clock clock, String redirectUri) {
        this.mapper = mapper;
        this.store = store;
        this.http = http;
        this.clock = clock;
        URI callback = URI.create(redirectUri);
        if (!"http".equals(callback.getScheme()) || !"127.0.0.1".equals(callback.getHost())
                || callback.getPort() < 1 || callback.getUserInfo() != null || callback.getQuery() != null
                || callback.getFragment() != null || !"/api/stocks/auth/ibkr/callback".equals(callback.getPath())) {
            throw new IllegalArgumentException("IBKR callback must use the local application callback.");
        }
        this.redirectUri = redirectUri;
    }

    synchronized boolean connected() {
        return store.read(TOKEN_KEY).map(token -> !string(token, "access_token").isBlank()
                && (expiry(token) > clock.instant().getEpochSecond() || !string(token, "refresh_token").isBlank()))
                .orElse(false);
    }

    synchronized String begin() {
        Metadata current = discovery();
        String clientId = client(current);
        String state = nonce(32);
        String verifier = nonce(48);
        pending = new Pending(state, verifier, clientId, clock.instant().getEpochSecond() + 600);
        var values = new LinkedHashMap<String, String>();
        values.put("response_type", "code");
        values.put("client_id", clientId);
        values.put("redirect_uri", redirectUri);
        values.put("scope", "mcp.read");
        values.put("state", state);
        values.put("code_challenge", digest(verifier));
        values.put("code_challenge_method", "S256");
        values.put("resource", RESOURCE.toString());
        return current.authorization() + "?" + form(values);
    }

    synchronized void finish(Map<String, String> values) {
        String state = values.getOrDefault("state", "");
        if (pending == null || pending.expiresAt() < clock.instant().getEpochSecond()
                || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("IBKR authorization expired or state did not match. Start connecting again.");
        }
        Pending accepted = pending;
        pending = null; // Matching callbacks are single-use, including provider cancellations.
        if (values.containsKey("error")) throw new IllegalStateException("IBKR authorization was not granted.");
        String code = values.getOrDefault("code", "");
        if (code.isBlank() || code.length() > 8192) throw new IllegalStateException("IBKR did not return an authorization code.");
        var request = new LinkedHashMap<String, String>();
        request.put("grant_type", "authorization_code");
        request.put("client_id", accepted.clientId());
        request.put("code", code);
        request.put("redirect_uri", redirectUri);
        request.put("code_verifier", accepted.verifier());
        request.put("resource", RESOURCE.toString());
        saveToken(tokenRequest(request), accepted.clientId(), "");
    }

    synchronized String accessToken() {
        Map<String, Object> token = store.read(TOKEN_KEY).orElseThrow(() -> new IllegalStateException("Connect your IBKR account first."));
        if (expiry(token) > clock.instant().getEpochSecond() + 60) return string(token, "access_token");
        String refresh = string(token, "refresh_token");
        if (refresh.isBlank()) {
            invalidate();
            throw new IllegalStateException("IBKR authorization expired. Reconnect your account.");
        }
        var request = new LinkedHashMap<String, String>();
        request.put("grant_type", "refresh_token");
        request.put("client_id", string(token, "client_id"));
        request.put("refresh_token", refresh);
        request.put("scope", "mcp.read");
        request.put("resource", RESOURCE.toString());
        JsonNode response = tokenRequest(request);
        saveToken(response, string(token, "client_id"), refresh);
        return response.path("access_token").asText();
    }

    synchronized void invalidate() { store.delete(TOKEN_KEY); }

    synchronized void disconnect() {
        var token = store.read(TOKEN_KEY);
        store.delete(TOKEN_KEY);
        pending = null;
        // Always disconnect locally, even if the provider is unavailable for revocation.
        if (token.isPresent() && metadata != null && metadata.revocation() != null) {
            Map<String, Object> value = token.get();
            String refresh = string(value, "refresh_token");
            String secret = refresh.isBlank() ? string(value, "access_token") : refresh;
            if (!secret.isBlank()) {
                try {
                    http.send("POST", metadata.revocation(), Map.of("Content-Type", "application/x-www-form-urlencoded"),
                            form(Map.of("token", secret, "client_id", string(value, "client_id"),
                                    "token_type_hint", refresh.isBlank() ? "access_token" : "refresh_token")));
                } catch (RuntimeException ignored) { /* Local tokens have already been removed. */ }
            }
        }
    }

    private Metadata discovery() {
        if (metadata != null) return metadata;
        JsonNode resource = get(RESOURCE_METADATA);
        if (!RESOURCE.toString().equals(resource.path("resource").asText())
                || !contains(resource.path("authorization_servers"), "https://api.ibkr.com")
                || !contains(resource.path("scopes_supported"), "mcp.read")
                || !contains(resource.path("bearer_methods_supported"), "header")) {
            throw new IllegalStateException("IBKR did not advertise compatible read-only MCP authorization.");
        }
        JsonNode server = get(SERVER_METADATA);
        if (!"https://api.ibkr.com".equals(server.path("issuer").asText())
                || !contains(server.path("response_types_supported"), "code")
                || !contains(server.path("grant_types_supported"), "authorization_code")
                || !contains(server.path("token_endpoint_auth_methods_supported"), "none")
                || !contains(server.path("code_challenge_methods_supported"), "S256")
                || !contains(server.path("scopes_supported"), "mcp.read")) {
            throw new IllegalStateException("IBKR does not currently advertise public-client PKCE login.");
        }
        metadata = new Metadata(endpoint(server, "authorization_endpoint"), endpoint(server, "token_endpoint"),
                endpoint(server, "registration_endpoint"), server.hasNonNull("revocation_endpoint")
                ? endpoint(server, "revocation_endpoint") : null);
        return metadata;
    }

    private String client(Metadata current) {
        var existing = store.read(CLIENT_KEY);
        if (existing.isPresent() && redirectUri.equals(existing.get().get("redirect_uri"))
                && !string(existing.get(), "client_id").isBlank()) return string(existing.get(), "client_id");
        var request = new LinkedHashMap<String, Object>();
        request.put("client_name", "Smart Inbox — read-only portfolio");
        request.put("redirect_uris", java.util.List.of(redirectUri));
        request.put("grant_types", java.util.List.of("authorization_code", "refresh_token"));
        request.put("response_types", java.util.List.of("code"));
        request.put("token_endpoint_auth_method", "none");
        request.put("scope", "mcp.read");
        var response = http.send("POST", current.registration(), Map.of("Content-Type", "application/json", "Accept", "application/json"), json(request));
        if (response.status() != 200 && response.status() != 201) {
            throw new IllegalStateException("IBKR client registration was rejected (HTTP " + response.status()
                    + "). This local callback may require provider approval; no account was connected.");
        }
        JsonNode registered = parse(response.body());
        String clientId = registered.path("client_id").asText();
        if (clientId.isBlank() || clientId.length() > 2048
                || !"none".equals(registered.path("token_endpoint_auth_method").asText("none"))) {
            throw new IllegalStateException("IBKR did not register a compatible public client.");
        }
        if (registered.has("redirect_uris") && !contains(registered.path("redirect_uris"), redirectUri)) {
            throw new IllegalStateException("IBKR did not accept the local callback URL.");
        }
        store.write(CLIENT_KEY, Map.of("client_id", clientId, "redirect_uri", redirectUri));
        return clientId;
    }

    private JsonNode tokenRequest(Map<String, String> values) {
        var reply = http.send("POST", discovery().token(), Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), form(values));
        if (reply.status() != 200) {
            if ("refresh_token".equals(values.get("grant_type")) && (reply.status() == 400 || reply.status() == 401)) invalidate();
            throw new IllegalStateException("IBKR authorization could not be completed. Reconnect or retry later.");
        }
        return parse(reply.body());
    }

    private void saveToken(JsonNode token, String clientId, String oldRefresh) {
        String access = token.path("access_token").asText();
        String type = token.path("token_type").asText();
        String scope = token.path("scope").asText("mcp.read");
        if (access.isBlank() || access.length() > 16384 || !access.matches("[A-Za-z0-9._~+/=-]+") || !"Bearer".equalsIgnoreCase(type))
            throw new IllegalStateException("IBKR returned an incompatible token.");
        if (!java.util.Set.of("mcp.read").equals(new java.util.HashSet<>(java.util.List.of(scope.trim().split(" +")))))
            throw new IllegalStateException("IBKR returned permissions beyond read-only access.");
        long seconds = token.path("expires_in").asLong(300);
        if (seconds <= 0 || seconds > 366L * 86400) throw new IllegalStateException("IBKR returned an invalid token lifetime.");
        String refresh = token.path("refresh_token").asText(oldRefresh);
        if (refresh.length() > 16384) throw new IllegalStateException("IBKR returned an invalid refresh token.");
        store.write(TOKEN_KEY, Map.of("access_token", access, "refresh_token", refresh, "client_id", clientId,
                "expires_at", clock.instant().getEpochSecond() + seconds));
    }

    private JsonNode get(URI uri) {
        var reply = http.send("GET", uri, Map.of("Accept", "application/json"), null);
        if (reply.status() != 200) throw new IllegalStateException("IBKR authorization discovery is unavailable. Retry later.");
        return parse(reply.body());
    }

    private JsonNode parse(String body) {
        try {
            if (body == null || body.length() > IbkrHttp.MAX_BYTES) throw new IllegalArgumentException();
            JsonNode value = mapper.readTree(body);
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            return value;
        } catch (Exception invalid) { throw new IllegalStateException("IBKR returned an unsupported authentication response."); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Unable to prepare IBKR authorization."); }
    }
    private static URI endpoint(JsonNode server, String field) {
        try { URI uri = URI.create(server.path(field).asText()); IbkrHttp.validateEndpoint(uri); return uri; }
        catch (Exception invalid) { throw new IllegalStateException("IBKR returned an unsupported authentication endpoint."); }
    }
    private static boolean contains(JsonNode list, String value) {
        if (!list.isArray()) return false;
        for (JsonNode item : list) if (value.equals(item.asText())) return true;
        return false;
    }
    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private String nonce(int size) { byte[] bytes = new byte[size]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String digest(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    private static String string(Map<String, Object> values, String key) { Object value = values.get(key); return value instanceof String text ? text : ""; }
    private static long expiry(Map<String, Object> token) { Object value = token.get("expires_at"); return value instanceof Number number ? number.longValue() : 0; }
    private record Metadata(URI authorization, URI token, URI registration, URI revocation) { }
    private record Pending(String state, String verifier, String clientId, long expiresAt) { }
}

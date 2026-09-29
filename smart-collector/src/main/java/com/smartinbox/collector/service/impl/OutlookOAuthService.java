package com.smartinbox.collector.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.collector.credentials.CredentialVaultService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OutlookOAuthService {
    private static final String SCOPES = "offline_access Mail.Read User.Read";
    private final CredentialVaultService vault;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    @Value("${outlook.client-id:}")
    private String configuredClientId;
    @Value("${outlook.tenant:organizations}")
    private String configuredTenant;
    @Value("${outlook.username:}")
    private String configuredUsername;

    public OutlookOAuthService(CredentialVaultService vault, ObjectMapper mapper) {
        this.vault = vault;
        this.mapper = mapper;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", !clientId().isBlank());
        status.put("connected", !vault.value("outlook.refresh-token", "").isBlank());
        status.put("account", vault.value("outlook.username", configuredUsername));
        return status;
    }

    public boolean isConnected() {
        return !clientId().isBlank() && !vault.value("outlook.refresh-token", "").isBlank();
    }

    public Map<String, Object> startDeviceAuthorization() throws Exception {
        if (clientId().isBlank()) {
            throw new IllegalStateException("请先在密钥管理中填写 Microsoft application client ID");
        }
        HttpResponse<String> response = postForm(deviceCodeEndpoint(), Map.of(
                "client_id", clientId(),
                "scope", SCOPES));
        if (response.statusCode() / 100 != 2) throw new IllegalStateException(errorMessage(response.body()));

        JsonNode json = mapper.readTree(response.body());
        String sessionId = UUID.randomUUID().toString();
        int expiresIn = json.path("expires_in").asInt(900);
        int interval = json.path("interval").asInt(5);
        sessions.put(sessionId, new DeviceSession(
                json.path("device_code").asText(),
                System.currentTimeMillis() + expiresIn * 1000L,
                interval));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("userCode", json.path("user_code").asText());
        result.put("verificationUri", json.path("verification_uri").asText("https://microsoft.com/devicelogin"));
        result.put("expiresIn", expiresIn);
        result.put("interval", interval);
        return result;
    }

    public Map<String, Object> pollDeviceAuthorization(String sessionId) throws Exception {
        DeviceSession session = sessions.get(sessionId);
        if (session == null || session.expiresAt() <= System.currentTimeMillis()) {
            sessions.remove(sessionId);
            return Map.of("state", "expired", "message", "登录代码已经过期，请重新连接");
        }

        HttpResponse<String> response = postForm(tokenEndpoint(), Map.of(
                "client_id", clientId(),
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "device_code", session.deviceCode()));
        JsonNode json = mapper.readTree(response.body());
        if (response.statusCode() / 100 == 2) {
            String refreshToken = json.path("refresh_token").asText();
            if (refreshToken.isBlank() || !vault.update("outlook.refresh-token", refreshToken)) {
                throw new IllegalStateException("Microsoft 已授权，但本地加密凭据保存失败");
            }
            sessions.remove(sessionId);
            return Map.of("state", "connected", "account", vault.value("outlook.username", configuredUsername));
        }

        String code = json.path("error").asText();
        if ("authorization_pending".equals(code)) return Map.of("state", "pending", "interval", session.interval());
        if ("slow_down".equals(code)) return Map.of("state", "pending", "interval", session.interval() + 5);
        sessions.remove(sessionId);
        return Map.of("state", "failed", "message", json.path("error_description").asText("Microsoft 登录失败"));
    }

    public Optional<String> accessToken() {
        String refreshToken = vault.value("outlook.refresh-token", "");
        if (clientId().isBlank() || refreshToken.isBlank()) return Optional.empty();
        try {
            HttpResponse<String> response = postForm(tokenEndpoint(), Map.of(
                    "client_id", clientId(),
                    "grant_type", "refresh_token",
                    "refresh_token", refreshToken,
                    "scope", SCOPES));
            if (response.statusCode() / 100 != 2) return Optional.empty();
            JsonNode json = mapper.readTree(response.body());
            String rotatedRefreshToken = json.path("refresh_token").asText();
            if (!rotatedRefreshToken.isBlank() && !rotatedRefreshToken.equals(refreshToken)) {
                vault.update("outlook.refresh-token", rotatedRefreshToken);
            }
            return Optional.ofNullable(json.path("access_token").textValue()).filter(value -> !value.isBlank());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private HttpResponse<String> postForm(String url, Map<String, String> values) throws Exception {
        String body = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String clientId() { return vault.value("outlook.client-id", configuredClientId == null ? "" : configuredClientId).trim(); }
    private String tenant() { return vault.value("outlook.tenant", configuredTenant == null ? "organizations" : configuredTenant).trim(); }
    private String deviceCodeEndpoint() { return "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/devicecode"; }
    private String tokenEndpoint() { return "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/token"; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String errorMessage(String body) {
        try { return mapper.readTree(body).path("error_description").asText("Microsoft OAuth 请求失败"); }
        catch (Exception ignored) { return "Microsoft OAuth 请求失败"; }
    }
    private record DeviceSession(String deviceCode, long expiresAt, int interval) {}
}

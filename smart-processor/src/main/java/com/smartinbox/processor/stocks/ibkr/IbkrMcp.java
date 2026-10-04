package com.smartinbox.processor.stocks.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal Streamable HTTP MCP client with a closed read-tool allowlist. */
final class IbkrMcp {
    private static final Set<String> READ_TOOLS = Set.of("get_account_positions", "get_account_balances",
            "get_account_summary", "get_pa_performance_all_periods", "search_contracts", "get_price_snapshot", "get_price_history");
    private static final List<String> PREFIXES = List.of("", "ibkr_", "interactive_brokers_ibkr_");
    private final ObjectMapper mapper;
    private final IbkrHttp http;
    private final IbkrAuthorization auth;
    private final Map<String, JsonNode> tools = new HashMap<>();
    private String sessionId;
    private String version = "2025-06-18";
    private boolean initialized;
    private long nextId;

    IbkrMcp(ObjectMapper mapper, IbkrHttp http, IbkrAuthorization auth) { this.mapper = mapper; this.http = http; this.auth = auth; }

    void reset() { sessionId = null; initialized = false; tools.clear(); }

    JsonNode call(String logicalName, Map<String, Object> arguments) {
        if (!READ_TOOLS.contains(logicalName)) throw new IllegalStateException("Only the supported IBKR read tools can be called.");
        initialize();
        JsonNode definition = tools.get(logicalName);
        if (definition == null) throw new IllegalStateException("This IBKR connection does not expose the required read tool: " + logicalName);
        validateArguments(definition.path("inputSchema"), mapper.valueToTree(arguments));
        JsonNode result = rpc("tools/call", Map.of("name", definition.path("name").asText(), "arguments", arguments), false);
        if (result.path("isError").asBoolean(false)) throw new IllegalStateException("IBKR could not supply this data. Check the connection and data permissions.");
        if (result.hasNonNull("structuredContent")) return result.get("structuredContent");
        JsonNode content = result.path("content");
        if (content.isArray()) {
            List<JsonNode> objects = new ArrayList<>();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    try { JsonNode value = mapper.readTree(block.path("text").asText()); if (value != null) objects.add(value); }
                    catch (Exception ignored) { /* Human-readable commentary is not a data schema. */ }
                }
            }
            if (objects.size() == 1) return objects.get(0);
        }
        throw new IllegalStateException("IBKR returned an unsupported data schema; no values were inferred.");
    }

    boolean supportsArgument(String logicalName, String argument) {
        initialize();
        JsonNode definition = tools.get(logicalName);
        return definition != null && definition.path("inputSchema").path("properties").has(argument);
    }

    boolean supportsTool(String logicalName) {
        initialize();
        return READ_TOOLS.contains(logicalName) && tools.containsKey(logicalName);
    }

    private void initialize() {
        if (initialized) return;
        JsonNode result = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "Smart Inbox", "version", "1.0.0")), false);
        String negotiated = result.path("protocolVersion").asText();
        if (!Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(negotiated)) {
            reset(); throw new IllegalStateException("IBKR uses an unsupported MCP protocol version.");
        }
        version = negotiated;
        rpc("notifications/initialized", Map.of(), true);
        Map<String, JsonNode> discovered = new HashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JsonNode listing = rpc("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), false);
            if (!listing.path("tools").isArray() || listing.path("tools").size() > 1000) throw new IllegalStateException("IBKR returned an unsupported tool catalog.");
            for (JsonNode definition : listing.path("tools")) {
                String actual = definition.path("name").asText();
                for (String logical : READ_TOOLS) {
                    if (PREFIXES.stream().anyMatch(prefix -> actual.equals(prefix + logical))) {
                        if (definition.path("annotations").has("readOnlyHint")
                                && !definition.path("annotations").path("readOnlyHint").asBoolean()) continue;
                        if (discovered.putIfAbsent(logical, definition) != null) throw new IllegalStateException("IBKR returned duplicate read-tool definitions.");
                    }
                }
            }
            cursor = listing.path("nextCursor").asText("");
            if (cursor.isBlank()) {
                tools.clear(); tools.putAll(discovered); initialized = true; return;
            }
            if (cursor.length() > 8192 || !cursors.add(cursor)) throw new IllegalStateException("IBKR tool pagination was incomplete.");
        }
        throw new IllegalStateException("IBKR tool catalog exceeded the supported page limit.");
    }

    private JsonNode rpc(String method, Map<String, Object> params, boolean notification) {
        long id = ++nextId;
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        if (!notification) request.put("id", id);
        request.put("method", method);
        request.set("params", mapper.valueToTree(params));
        String token = auth.accessToken();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", version);
        if (sessionId != null) headers.put("Mcp-Session-Id", sessionId);
        var reply = http.send("POST", IbkrAuthorization.RESOURCE, headers, request.toString());
        if (reply.status() == 401 || reply.status() == 403) {
            reset();
            if (reply.status() == 401) auth.invalidate();
            throw new IllegalStateException("IBKR authorization is unavailable. Reconnect and grant read-only access.");
        }
        if (reply.status() == 404 && sessionId != null) { reset(); throw new IllegalStateException("IBKR session expired. Refresh again to reconnect."); }
        if (reply.status() < 200 || reply.status() >= 300) throw new IllegalStateException("IBKR request failed (HTTP " + reply.status() + "). Retry later.");
        String newSession = reply.header("Mcp-Session-Id");
        if (newSession != null) {
            if (newSession.isBlank() || newSession.length() > 1024 || newSession.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) throw new IllegalStateException("IBKR returned an invalid session identifier.");
            sessionId = newSession;
        }
        if (notification) return mapper.createObjectNode();
        JsonNode message = response(reply.body(), id);
        if (message.has("error")) throw new IllegalStateException("IBKR could not complete the requested read operation.");
        if (!message.has("result")) throw new IllegalStateException("IBKR returned an unsupported MCP response.");
        return message.get("result");
    }

    private JsonNode response(String body, long id) {
        if (body == null || body.length() > IbkrHttp.MAX_BYTES) throw new IllegalStateException("IBKR response exceeds size limit.");
        try {
            if (body.stripLeading().startsWith("{")) {
                JsonNode value = mapper.readTree(body);
                if (value.path("id").asLong(-1) == id && "2.0".equals(value.path("jsonrpc").asText())) return value;
            } else {
                StringBuilder data = new StringBuilder();
                for (String line : (body + "\n\n").split("\\r?\\n", -1)) {
                    if (line.isEmpty() && !data.isEmpty()) {
                        JsonNode value = mapper.readTree(data.toString());
                        data.setLength(0);
                        if (value.path("id").asLong(-1) == id && "2.0".equals(value.path("jsonrpc").asText())) return value;
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    }
                }
            }
        } catch (Exception malformed) { throw new IllegalStateException("IBKR returned an unsupported MCP response."); }
        throw new IllegalStateException("IBKR response did not match the request.");
    }

    static void validateArguments(JsonNode schema, JsonNode values) {
        if (!schema.isObject() || !"object".equals(schema.path("type").asText("object"))) unsupportedSchema();
        if (schema.has("oneOf") || schema.has("anyOf") || schema.has("allOf") || schema.has("$ref")) unsupportedSchema();
        JsonNode required = schema.path("required");
        if (!required.isMissingNode() && !required.isArray()) unsupportedSchema();
        for (JsonNode field : required) if (!values.has(field.asText()) || values.path(field.asText()).isNull()) unsupportedSchema();
        var fields = values.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode definition = schema.path("properties").path(field.getKey());
            if (!definition.isObject()) unsupportedSchema();
            validateValue(definition, field.getValue());
        }
    }

    private static void validateValue(JsonNode schema, JsonNode value) {
        if (schema.path("anyOf").isArray()) {
            for (JsonNode alternative : schema.path("anyOf")) {
                try { validateValue(alternative, value); return; }
                catch (IllegalStateException ignored) { /* Try another explicit schema alternative. */ }
            }
            unsupportedSchema();
        }
        if (schema.has("oneOf") || schema.has("allOf") || schema.has("$ref")) unsupportedSchema();
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> false;
        };
        if (!valid) unsupportedSchema();
        if (schema.path("enum").isArray()) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) if (option.equals(value)) found = true;
            if (!found) unsupportedSchema();
        }
        if (value.isArray()) for (JsonNode element : value) validateValue(schema.path("items"), element);
    }
    private static void unsupportedSchema() { throw new IllegalStateException("IBKR changed the required tool parameters. This schema is not yet supported; no arguments were guessed."); }
}

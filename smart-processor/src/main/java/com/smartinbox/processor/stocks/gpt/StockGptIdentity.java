package com.smartinbox.processor.stocks.gpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;

/** Access tokens are opaque and are never used as identity assertions. */
final class StockGptIdentity {
    private StockGptIdentity() { }
    static JsonNode verify(String token, String clientId, String nonce, JsonNode jwks,
                           ObjectMapper mapper, Clock clock) {
        try {
            if (token == null || token.length() > 32_768) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw invalid();
            JsonNode header = mapper.readTree(decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText()) || header.has("crit")) throw invalid();
            String kid = header.path("kid").asText();
            if (kid.isBlank()) throw invalid();
            JsonNode key = null;
            for (JsonNode candidate : jwks.path("keys")) {
                if (kid.equals(candidate.path("kid").asText()) && "RSA".equals(candidate.path("kty").asText())
                        && (!candidate.has("use") || "sig".equals(candidate.path("use").asText()))
                        && (!candidate.has("alg") || "RS256".equals(candidate.path("alg").asText()))) {
                    if (key != null) throw invalid();
                    key = candidate;
                }
            }
            if (key == null) throw invalid();
            BigInteger modulus = new BigInteger(1, decode(key.path("n").asText()));
            if (modulus.bitLength() < 2048) throw invalid();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,
                    new BigInteger(1, decode(key.path("e").asText()))));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decode(parts[2]))) throw invalid();
            JsonNode claims = mapper.readTree(decode(parts[1]));
            long now = clock.instant().getEpochSecond();
            if (!StockGptService.ISSUER.equals(claims.path("iss").asText())
                    || claims.path("sub").asText().isBlank()
                    || !claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() <= now - 5
                    || !claims.path("iat").isIntegralNumber() || claims.path("iat").asLong() > now + 5
                    || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || claims.path("nbf").asLong() > now + 5))) throw invalid();
            JsonNode audience = claims.path("aud");
            boolean matches = audience.isTextual() && clientId.equals(audience.asText());
            if (audience.isArray()) for (JsonNode entry : audience) matches |= clientId.equals(entry.asText());
            if (!matches || (audience.isArray() && audience.size() > 1 && !clientId.equals(claims.path("azp").asText()))
                    || (claims.has("azp") && !clientId.equals(claims.path("azp").asText()))) throw invalid();
            if (nonce != null && !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),
                    claims.path("nonce").asText().getBytes(StandardCharsets.UTF_8))) throw invalid();
            return claims;
        } catch (Exception e) { throw invalid(); }
    }
    static String keyId(String token, ObjectMapper mapper) {
        try {
            if (token == null || token.length() > 32_768) throw invalid();
            return mapper.readTree(decode(token.split("\\.", -1)[0])).path("kid").asText();
        } catch (Exception e) { throw invalid(); }
    }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static IllegalStateException invalid() { return new IllegalStateException("ChatGPT 身份验证失败，请重新登录"); }
}

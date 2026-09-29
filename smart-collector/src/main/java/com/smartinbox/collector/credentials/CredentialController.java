package com.smartinbox.collector.credentials;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/credentials")
public class CredentialController {
    private final CredentialVaultService vault;
    public CredentialController(CredentialVaultService vault) { this.vault = vault; }
    @GetMapping public Object list() { return vault.listMasked(); }
    @PostMapping("/authorize") public ResponseEntity<?> authorize(@RequestBody PasswordRequest request, HttpServletRequest httpRequest) {
        String token = vault.authorize(request.password(), httpRequest.getRemoteAddr());
        return token == null ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Management password rejected")) : ResponseEntity.ok(Map.of("sessionToken", token, "expiresInSeconds", vault.expiresInSeconds(token)));
    }
    @GetMapping("/{id}/reveal") public ResponseEntity<?> reveal(@PathVariable String id, @RequestHeader(value = "X-Credential-Session", required = false) String token) {
        if (!vault.isAuthorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return vault.reveal(id).<ResponseEntity<?>>map(value -> ResponseEntity.ok(Map.of("value", value))).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PutMapping("/{id}") public ResponseEntity<?> update(@PathVariable String id, @RequestHeader(value = "X-Credential-Session", required = false) String token, @RequestBody ValueRequest request) {
        if (!vault.isAuthorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return vault.update(id, request.value()) ? ResponseEntity.ok(Map.of("restartRequired", vault.requiresRestart(id))) : ResponseEntity.badRequest().body(Map.of("message", "A non-empty value is required"));
    }
    public record PasswordRequest(String password) {} public record ValueRequest(String value) {}
}

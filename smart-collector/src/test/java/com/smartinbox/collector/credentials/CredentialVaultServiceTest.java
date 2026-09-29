package com.smartinbox.collector.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CredentialVaultServiceTest {
    @TempDir Path temporaryHome;
    private String originalUserHome;

    @BeforeEach void useTemporaryHome() { originalUserHome = System.getProperty("user.home"); System.setProperty("user.home", temporaryHome.toString()); }
    @AfterEach void restoreHome() { System.setProperty("user.home", originalUserHome); }

    @Test void rejectsWrongPasswordsAndTemporarilyLocksRepeatedFailures() {
        CredentialVaultService vault = vault();
        for (int attempt = 0; attempt < 5; attempt++) assertNull(vault.authorize("wrong", "test-client"));
        assertTrue(vault.isLocked("test-client"));
        assertNull(vault.authorize("unit-test-vault-password", "test-client"));
    }

    @Test void masksListAndAllowsAuthenticatedRevealUpdateAndReload() {
        CredentialVaultService vault = vault();
        assertEquals("••••••••", vault.listMasked().stream().filter(entry -> entry.id().equals("gmail.username")).findFirst().orElseThrow().maskedValue());
        assertFalse(vault.requiresRestart("gmail.username"));
        assertTrue(vault.requiresRestart("rocketmq.secret-key"));

        String token = vault.authorize("unit-test-vault-password", "test-client");
        assertNotNull(token);
        assertTrue(vault.isAuthorized(token));
        assertEquals("mailbox@example.test", vault.reveal("gmail.username").orElseThrow());
        assertTrue(vault.update("gmail.username", "replacement@example.test"));

        CredentialVaultService reloaded = vault();
        assertEquals("replacement@example.test", reloaded.reveal("gmail.username").orElseThrow());
        assertEquals("••••••••", reloaded.listMasked().stream().filter(entry -> entry.id().equals("gmail.username")).findFirst().orElseThrow().maskedValue());
    }

    private CredentialVaultService vault() {
        MockEnvironment environment = new MockEnvironment().withProperty("smart.credentials.management-password-sha256", "bd1a0bc3c88179730eebc1daf7f55ff331bc6d3b87d261d57d1d8eff35a1d134").withProperty("spring.mail.username", "mailbox@example.test");
        CredentialVaultService vault = new CredentialVaultService(environment, new ObjectMapper());
        vault.initialize();
        return vault;
    }
}

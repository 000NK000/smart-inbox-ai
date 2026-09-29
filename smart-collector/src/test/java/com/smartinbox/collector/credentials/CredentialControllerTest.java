package com.smartinbox.collector.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CredentialControllerTest {
    @TempDir Path temporaryHome;
    private String originalUserHome;

    @BeforeEach void useTemporaryHome() { originalUserHome = System.getProperty("user.home"); System.setProperty("user.home", temporaryHome.toString()); }
    @AfterEach void restoreHome() { System.setProperty("user.home", originalUserHome); }

    @Test void keepsListsMaskedAndGatesRevealAndUpdateBehindAuthorization() {
        CredentialVaultService vault = new CredentialVaultService(new MockEnvironment().withProperty("smart.credentials.management-password-sha256", "bd1a0bc3c88179730eebc1daf7f55ff331bc6d3b87d261d57d1d8eff35a1d134").withProperty("spring.mail.username", "mailbox@example.test"), new ObjectMapper());
        vault.initialize();
        CredentialController controller = new CredentialController(vault);
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setRemoteAddr("test-client");

        assertEquals(HttpStatus.UNAUTHORIZED, controller.authorize(new CredentialController.PasswordRequest("wrong"), request).getStatusCode());
        Object listed = controller.list();
        assertTrue(listed instanceof List<?>);
        assertTrue(((List<?>) listed).stream().allMatch(item -> item instanceof CredentialVaultService.MaskedCredential));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.reveal("gmail.username", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.update("gmail.username", null, new CredentialController.ValueRequest("replacement@example.test")).getStatusCode());

        Map<?, ?> authorization = (Map<?, ?>) controller.authorize(new CredentialController.PasswordRequest("unit-test-vault-password"), request).getBody();
        String token = (String) authorization.get("sessionToken");
        assertTrue(controller.reveal("gmail.username", token).getStatusCode().is2xxSuccessful());
        assertTrue(controller.update("gmail.username", token, new CredentialController.ValueRequest("replacement@example.test")).getStatusCode().is2xxSuccessful());
    }
}

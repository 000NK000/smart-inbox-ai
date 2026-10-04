package com.smartinbox.processor.stocks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockSecretStoreTest {
    @TempDir Path directory;
    @Test void secretsSurviveRestartWithoutPlaintextOnDiskAndDisconnectDeletesThem() throws Exception {
        var store=new StockSecretStore(directory.toString(),new ObjectMapper());
        store.write("ibkr",Map.of("access_token","test-only-token","expiry",1234));
        String file=new String(Files.readAllBytes(directory.resolve("ibkr.enc")),java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(file.contains("test-only-token"));
        var restarted=new StockSecretStore(directory.toString(),new ObjectMapper());
        assertEquals("test-only-token",restarted.read("ibkr").orElseThrow().get("access_token"));
        restarted.delete("ibkr"); assertTrue(restarted.read("ibkr").isEmpty());
    }
    @Test void tamperingAndSwappingProvidersFailClosed() throws Exception {
        var store=new StockSecretStore(directory.toString(),new ObjectMapper());
        store.write("ibkr",Map.of("token","sample"));
        Files.copy(directory.resolve("ibkr.enc"),directory.resolve("gpt.enc"));
        assertThrows(IllegalStateException.class,()->store.read("gpt"));
        byte[] bytes=Files.readAllBytes(directory.resolve("ibkr.enc")); bytes[20]^=1; Files.write(directory.resolve("ibkr.enc"),bytes);
        assertThrows(IllegalStateException.class,()->store.read("ibkr"));
    }
    @Test void secretNamesCannotEscapeVault() {
        var store=new StockSecretStore(directory.toString(),new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->store.write("../credentials",Map.of()));
        assertThrows(IllegalArgumentException.class,()->store.read("/private"));
    }
}

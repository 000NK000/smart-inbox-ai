package com.smartinbox.processor.stocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/** Dedicated local OAuth store. Never serialize this service or include token values in errors. */
@Service
public class StockSecretStore {
    private final Path directory;
    private final ObjectMapper json;
    private byte[] key;
    public StockSecretStore(@Value("${stocks.vault-path:.smart-inbox/stock-vault}") String directory, ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.json = json;
    }
    public synchronized Optional<Map<String,Object>> read(String name) {
        Path file = file(name);
        if (!Files.exists(file)) return Optional.empty();
        try {
            initialize();
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 28 || bytes.length > 1_048_576) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes,12)));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Optional.of(json.readValue(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),new TypeReference<>(){}));
        } catch (Exception ignored) { throw new IllegalStateException("股票账户授权无法读取，请重新连接账户"); }
    }
    public synchronized void write(String name, Map<String,Object> values) {
        Path file = file(name);
        try {
            initialize();
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] plaintext = json.writeValueAsBytes(values);
            if (plaintext.length > 1_000_000) throw new IllegalArgumentException();
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] payload = Arrays.copyOf(iv,iv.length+encrypted.length);
            System.arraycopy(encrypted,0,payload,iv.length,encrypted.length);
            atomicWrite(file,payload);
        } catch (Exception ignored) { throw new IllegalStateException("股票账户授权保存失败，请检查本地文件权限"); }
    }
    public synchronized void delete(String name) {
        try { Files.deleteIfExists(file(name)); }
        catch (Exception ignored) { throw new IllegalStateException("股票账户授权移除失败"); }
    }
    private Path file(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid secret key");
        return directory.resolve(name+".enc");
    }
    private void initialize() throws Exception {
        if (key != null) return;
        Files.createDirectories(directory); restrict(directory,true);
        Path keyPath = directory.resolve("local.key");
        if (Files.exists(keyPath)) key = Files.readAllBytes(keyPath);
        else {
            KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
            byte[] generated = generator.generateKey().getEncoded();
            try { Files.write(keyPath, generated, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { }
            key = Files.readAllBytes(keyPath);
        }
        restrict(keyPath,false);
        if (key.length != 32) { key = null; throw new IllegalStateException(); }
    }
    private void atomicWrite(Path file,byte[] bytes) throws Exception {
        Path temp = Files.createTempFile(directory,"stock-", ".tmp");
        try {
            restrict(temp,false); Files.write(temp,bytes);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            restrict(file,false);
        } finally { Files.deleteIfExists(temp); }
    }
    private void restrict(Path path,boolean folder) throws Exception {
        AclFileAttributeView acl = Files.getFileAttributeView(path,AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal principal = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (folder) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(builder.build()));
        } else Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(folder?"rwx------":"rw-------"));
    }
}

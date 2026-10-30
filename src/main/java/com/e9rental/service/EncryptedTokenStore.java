package com.e9rental.service;
import com.e9rental.connector.GmailFailure;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class EncryptedTokenStore {
    private final String key;
    private final Path path;
    public EncryptedTokenStore(@Value("${TOKEN_ENCRYPTION_KEY:}") String key,
            @Value("${GMAIL_TOKEN_FILE:D:/E9Rental-Secrets/gmail-runtime/gmail-token.enc}") String path) { this.key=key; this.path=Path.of(path); }
    public void validate() {
        try { if (Base64.getDecoder().decode(key).length != 32) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new GmailFailure(503,"Set TOKEN_ENCRYPTION_KEY to a Base64-encoded 32-byte key"); }
    }
    public synchronized void save(byte[] plain) {
        persist(plain,false);
    }
    /** Persist a first OAuth grant without ever replacing a token created by another operation. */
    public synchronized void saveIfAbsent(byte[] plain) {
        persist(plain,true);
    }
    private void persist(byte[] plain,boolean createOnly) {
        validate();
        try {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=cipher.doFinal(plain);
            byte[] result=new byte[iv.length+encrypted.length];
            System.arraycopy(iv,0,result,0,iv.length); System.arraycopy(encrypted,0,result,iv.length,encrypted.length);
            Path absolute=path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
            if(createOnly) {
                // CREATE_NEW is exclusive at the filesystem level; an existing encrypted token is never replaced.
                try(var channel=java.nio.channels.FileChannel.open(absolute,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    var buffer=java.nio.ByteBuffer.wrap(result);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
            } else {
                Path temp=Files.createTempFile(absolute.getParent(),"token-", ".enc");
                try { Files.write(temp,result); Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); }
            }
        } catch (Exception e) { throw new GmailFailure(503,"Cannot securely persist OAuth token"); }
    }
    public synchronized byte[] load() {
        validate();
        try {
            if (!Files.exists(path)) return null;
            byte[] data=Files.readAllBytes(path);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),
                    new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
            return cipher.doFinal(Arrays.copyOfRange(data,12,data.length));
        } catch (Exception e) { throw new GmailFailure(503,"Cannot decrypt OAuth token; check encryption key"); }
    }
}

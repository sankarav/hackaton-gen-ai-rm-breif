package com.demo.rmbrief.capture.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Raw capture media (photos, audio) encrypted at rest with AES-256-GCM on a local volume.
 * Swap for an S3/MinIO implementation in production; callers only see opaque keys.
 */
@Component
public class MediaStore {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path root;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MediaStore(@Value("${app.media.root}") String root,
                      @Value("${app.media.encryption-key}") String base64Key) throws IOException {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "MEDIA_ENCRYPTION_KEY is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("MEDIA_ENCRYPTION_KEY must decode to 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.root = Path.of(root);
        Files.createDirectories(this.root);
    }

    public String put(byte[] plaintext) {
        String storageKey = UUID.randomUUID().toString();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            Files.write(path(storageKey), ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array());
            return storageKey;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Media encryption failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] get(String storageKey) {
        try {
            byte[] blob = Files.readAllBytes(path(storageKey));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Media decryption failed for " + storageKey, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(path(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String storageKey) {
        return root.resolve(UUID.fromString(storageKey).toString());
    }
}

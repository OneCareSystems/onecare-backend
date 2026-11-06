package com.onecare.backend.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM encryption for system backup files (SDS 2.5.3).
 *
 * Wire format: [12-byte IV][GCM tag + ciphertext as produced by Cipher].
 * A fresh random IV is generated for every encryption, and the 16-byte GCM
 * tag authenticates the ciphertext, so a tampered file fails to decrypt.
 */
public final class AesGcmEncryptor {

    public static final int IV_LENGTH = 12;
    public static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEncryptor(byte[] key) {
        if (key == null || key.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Backup encryption key must be exactly 32 bytes (AES-256)");
        }
        this.key = Arrays.copyOf(key, key.length);
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] output = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
            return output;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt backup data", exception);
        }
    }

    public byte[] decrypt(byte[] ivAndCiphertext) {
        if (ivAndCiphertext == null || ivAndCiphertext.length <= IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted backup data is malformed");
        }
        try {
            byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, IV_LENGTH, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to decrypt backup data: wrong key or corrupted file", exception);
        }
    }
}

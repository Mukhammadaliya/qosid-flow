package uz.greenwhite.qosid.settings;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for at-rest secrets in the settings DB. Key derived from
 * the raw key string via SHA-256 (so any-length key works). Plain POJO (no
 * Spring) — the gateway wires it as a {@code @Bean} from
 * {@code gateway.settings.encryption-key}; the ops sidecar constructs its own.
 * Blank key → {@link #isEnabled()} false; callers must not store secrets then.
 */
@Slf4j
public class SettingsCipher {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec keySpec;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public SettingsCipher(String rawKey) {
        this.enabled = rawKey != null && !rawKey.isBlank();
        if (enabled) {
            this.keySpec = new SecretKeySpec(sha256(rawKey), "AES");
            log.info("Settings encryption ENABLED (AES-GCM)");
        } else {
            this.keySpec = null;
            log.warn("Settings encryption DISABLED: encryption key is blank — secrets cannot be stored");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plain) {
        requireEnabled();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Settings encrypt failed", e);
        }
    }

    public String decrypt(String cipherText) {
        requireEnabled();
        try {
            byte[] in = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(in, GCM_IV_LENGTH, in.length - GCM_IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Settings decrypt failed", e);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Settings encryption disabled: set gateway.settings.encryption-key");
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

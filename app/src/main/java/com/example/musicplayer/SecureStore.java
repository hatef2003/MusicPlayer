package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores small secrets (API keys, bot tokens) encrypted with an AES-256-GCM key
 * held in the Android Keystore. The key never leaves the device, and the prefs
 * file is excluded from backups (see res/xml/backup_rules.xml), so a restored
 * backup can never carry the secrets to another device.
 *
 * <p>If decryption fails (e.g. the Keystore key was wiped) the value is treated
 * as absent and the user simply re-enters it.</p>
 */
final class SecureStore {

    private static final String TAG = "SecureStore";
    static final String PREFS_NAME = "secure_settings";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "music_player_secrets";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    SecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    boolean contains(String key) {
        return prefs.contains(key);
    }

    String get(String key, String def) {
        String stored = prefs.getString(key, null);
        if (stored == null) return def;
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            ByteBuffer bb = ByteBuffer.wrap(blob);
            byte[] iv = new byte[IV_LEN];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt '" + key + "' — discarding it", e);
            prefs.edit().remove(key).apply();
            return def;
        }
    }

    void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            // Never fall back to plaintext: the secret is simply not persisted.
            Log.e(TAG, "Could not encrypt '" + key + "'", e);
        }
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}

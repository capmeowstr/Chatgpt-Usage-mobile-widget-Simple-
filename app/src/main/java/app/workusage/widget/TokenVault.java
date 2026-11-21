package app.workusage.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "work-usage-session";
    private static final String SESSION = "encrypted_session";
    private static final int MAX_RECORD_LENGTH = 128 * 1024;
    private final SharedPreferences privateState;

    TokenVault(Context context) {
        privateState = context.getSharedPreferences("private_session", Context.MODE_PRIVATE);
    }

    boolean exists() {
        return privateState.contains(SESSION);
    }

    CodexClient.Session load() throws GeneralSecurityException, IOException, JSONException {
        try {
            String encrypted = privateState.getString(SESSION, null);
            if (encrypted == null) {
                return null;
            }
            byte[][] record = decodeRecord(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, record[0]));
            byte[] plain = cipher.doFinal(record[1]);
            try {
                return CodexClient.Session.fromStorage(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (ClassCastException | ProviderException e) {
            throw new GeneralSecurityException("Invalid credential record", e);
        }
    }

    void save(CodexClient.Session session)
            throws GeneralSecurityException, IOException, JSONException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] plain = session.toJson().toString().getBytes(StandardCharsets.UTF_8);
            String encrypted;
            try {
                Base64.Encoder base64 = Base64.getEncoder();
                encrypted = base64.encodeToString(cipher.getIV()) + ":"
                        + base64.encodeToString(cipher.doFinal(plain));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            if (!privateState.edit().putString(SESSION, encrypted).commit()) {
                throw new IOException("Could not save account session");
            }
        } catch (ProviderException e) {
            throw new GeneralSecurityException("Credential storage is unavailable", e);
        }
    }

    boolean clear() {
        boolean removed = privateState.edit().remove(SESSION).commit();
        try {
            credentialStore().deleteEntry(KEY_ALIAS);
            return true;
        } catch (GeneralSecurityException | IOException | ProviderException e) {
            return removed;
        }
    }

    static byte[][] decodeRecord(String encrypted) throws GeneralSecurityException {
        int separator = encrypted.indexOf(':');
        if (encrypted.length() > MAX_RECORD_LENGTH || separator != 16) {
            throw new GeneralSecurityException("Invalid credential record");
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(encrypted.substring(0, separator));
            byte[] ciphertext = base64.decode(encrypted.substring(separator + 1));
            if (iv.length == 12 && ciphertext.length > 16) {
                return new byte[][]{iv, ciphertext};
            }
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid credential encoding", e);
        }
        throw new GeneralSecurityException("Invalid credential record");
    }

    private static KeyStore credentialStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = credentialStore();
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }
        if (!create) {
            throw new GeneralSecurityException("Credential key is missing");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}

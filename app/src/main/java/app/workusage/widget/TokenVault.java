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
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "work-usage-session";
    private static final String SESSION = "encrypted_session";
    private final SharedPreferences privateState;

    TokenVault(Context context) {
        privateState = context.getSharedPreferences("private_session", Context.MODE_PRIVATE);
    }

    boolean exists() {
        return privateState.contains(SESSION);
    }

    CodexClient.Session load() throws GeneralSecurityException, IOException, JSONException {
        String encrypted = privateState.getString(SESSION, null);
        if (encrypted == null) {
            return null;
        }
        String[] segments = encrypted.split(":", 2);
        if (segments.length != 2) {
            throw new GeneralSecurityException("Invalid credential record");
        }
        Base64.Decoder base64 = Base64.getDecoder();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, base64.decode(segments[0])));
        byte[] plain = cipher.doFinal(base64.decode(segments[1]));
        return CodexClient.Session.fromStorage(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
    }

    void save(CodexClient.Session session)
            throws GeneralSecurityException, IOException, JSONException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] plain = session.toJson().toString().getBytes(StandardCharsets.UTF_8);
        Base64.Encoder base64 = Base64.getEncoder();
        String encrypted = base64.encodeToString(cipher.getIV()) + ":"
                + base64.encodeToString(cipher.doFinal(plain));
        if (!privateState.edit().putString(SESSION, encrypted).commit()) {
            throw new IOException("Could not save account session");
        }
    }

    void clear() {
        privateState.edit().remove(SESSION).commit();
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
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

package app.workusage.widget;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class CodexClient {
    static final String ISSUER = "https://auth.openai.com";
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String DASHBOARD = "https://chatgpt.com/settings/usage";
    static final String SECURITY_SETTINGS = "https://chatgpt.com/#settings/Security";
    static final String DEVICE_VERIFICATION_URL = ISSUER + "/codex/device";
    static final String DEVICE_REDIRECT = ISSUER + "/deviceauth/callback";
    private static final String QUOTA_BASE = "https://chatgpt.com/backend-api/wham";
    private static final String TOKEN_PATH = "/oauth/token";
    private static final String ACCOUNTS_TOKEN_PATH = "/api/accounts/oauth/token";
    private static final String TOKEN_URL = ISSUER + TOKEN_PATH;
    private static final String DEVICE_API = ISSUER + "/api/accounts/deviceauth";
    private static final String APP_ID = "work_usage_android";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int AUTH_READ_TIMEOUT_MS = 30000;
    private static final int USAGE_READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final long MAX_DEVICE_SECONDS = TimeUnit.MINUTES.toSeconds(15);
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/=-]{1,24576}");
    private static final Pattern USER_CODE = Pattern.compile("[A-Za-z0-9-]{4,32}");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private final Connections connections;

    CodexClient(Connections connections) {
        this.connections = connections;
    }

    interface Connections {
        HttpURLConnection open(URI url) throws IOException;
    }

    interface ApprovalGate {
        void awaitForeground(long deadline) throws InterruptedException;
    }

    static String authorizeUrl(String redirect, String challenge, String state) {
        return ISSUER + "/oauth/authorize?" + params(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "redirect_uri", redirect,
                "scope", "openid profile email offline_access",
                "code_challenge", challenge,
                "code_challenge_method", "S256",
                "id_token_add_organizations", "true",
                "codex_cli_simplified_flow", "true",
                "state", state,
                "originator", APP_ID);
    }

    Session exchange(String code, String redirect, String verifier)
            throws IOException, JSONException {
        JSONObject response = request(TOKEN_URL, "application/x-www-form-urlencoded", params(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirect,
                "client_id", CLIENT_ID,
                "code_verifier", verifier), null);
        return Session.fromTokenResponse(response, null);
    }

    Session renew(Session previous) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("client_id", CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", previous.refreshToken);
        JSONObject response = request(TOKEN_URL, "application/json", body.toString(), null);
        return Session.fromTokenResponse(response, previous);
    }

    DeviceCode requestDeviceCode() throws IOException, JSONException {
        JSONObject body = new JSONObject().put("client_id", CLIENT_ID);
        JSONObject result = request(DEVICE_API + "/usercode", "application/json", body.toString(), null);
        String code = stringOrEmpty(result, "user_code");
        if (code.isEmpty()) {
            code = stringOrEmpty(result, "usercode");
        }
        String id = stringOrEmpty(result, "device_auth_id");
        if (!USER_CODE.matcher(code).matches() || id.isEmpty() || id.length() > 512) {
            throw new JSONException("Invalid device code response");
        }
        long interval = Math.max(1, result.optLong("interval", 5));
        long lifetime = result.optLong("expires_in", MAX_DEVICE_SECONDS);
        if (lifetime <= 0) {
            throw new DeviceCodeExpired();
        }
        return new DeviceCode(id, code, Math.min(interval, MAX_DEVICE_SECONDS),
                Math.min(lifetime, MAX_DEVICE_SECONDS));
    }

    DeviceGrant awaitDeviceApproval(DeviceCode device, ApprovalGate approvalGate)
            throws IOException, JSONException, InterruptedException {
        String body = new JSONObject().put("device_auth_id", device.id)
                .put("user_code", device.userCode).toString();
        long deadline = device.createdAt + TimeUnit.SECONDS.toMillis(device.expiresInSeconds);
        long delay = device.intervalSeconds;
        int connectionErrors = 0;
        while (true) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) {
                throw new DeviceCodeExpired();
            }
            Thread.sleep(Math.min(left, TimeUnit.SECONDS.toMillis(delay)));
            approvalGate.awaitForeground(deadline);
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new DeviceCodeExpired();
            }
            try {
                JSONObject reply = request(DEVICE_API + "/token", "application/json", body, null);
                return new DeviceGrant(reply.getString("authorization_code"), reply.getString("code_verifier"));
            } catch (HttpFailure e) {
                connectionErrors = 0;
                if ("expired_token".equals(e.code)) {
                    throw new DeviceCodeExpired();
                }
                if ("access_denied".equals(e.code) || "device_auth_disabled".equals(e.code)) {
                    throw e;
                }
                if (e.status == 429 || "slow_down".equals(e.code)) {
                    delay = Math.max(Math.max(delay + 5, device.intervalSeconds), e.retryAfterSeconds);
                } else if (e.status != 403 && e.status != 404 && !"authorization_pending".equals(e.code)) {
                    throw e;
                }
            } catch (IOException e) {
                if (++connectionErrors >= 3) {
                    throw e;
                }
            }
        }
    }

    UsageSnapshot getSnapshot(Session session) throws IOException, JSONException {
        JSONObject usage = request(QUOTA_BASE + "/usage", null, null, session);
        UsageSnapshot snapshot = UsageSnapshot.fromUsage(usage, System.currentTimeMillis());
        if (snapshot.earnedResets == null) {
            try {
                JSONObject resets = request(QUOTA_BASE + "/rate-limit-reset-credits",
                        null, null, session);
                snapshot = snapshot.withResets(UsageSnapshot.readResets(resets));
            } catch (IOException | JSONException ignored) {
                // The reset count and the two quota windows can be available independently.
            }
        }
        return snapshot;
    }

    private JSONObject request(String url, String contentType, String body, Session session)
            throws IOException, JSONException {
        URI endpoint = URI.create(url);
        boolean canRedirectTokenPost = url.equals(TOKEN_URL);
        while (true) {
            HttpURLConnection connection = connections.open(endpoint);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(session == null ? AUTH_READ_TIMEOUT_MS : USAGE_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "WorkUsage/1.6 (Android)");
            if (session != null) {
                connection.setRequestProperty("originator", APP_ID);
                connection.setRequestProperty("Authorization", "Bearer " + session.accessToken);
                if (!session.accountId.isEmpty()) {
                    connection.setRequestProperty("ChatGPT-Account-Id", session.accountId);
                }
            }
            try {
                if (body != null) {
                    byte[] content = body.getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", contentType);
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(content);
                    }
                }
                int status = connection.getResponseCode();
                if (status < 100) {
                    throw new ProtocolException("Missing HTTP status");
                }
                if ((status == 307 || status == 308) && canRedirectTokenPost) {
                    URI redirected = tokenRedirect(endpoint, connection.getHeaderField("Location"));
                    if (redirected != null) {
                        endpoint = redirected;
                        canRedirectTokenPost = false;
                        continue;
                    }
                }
                if (status < 200 || status >= 300) {
                    throw new HttpFailure(status, errorCode(connection), retryAfter(connection));
                }
                try (InputStream in = connection.getInputStream()) {
                    return new JSONObject(readBody(in, MAX_RESPONSE_BYTES));
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private static URI tokenRedirect(URI endpoint, String location) {
        if (location == null || location.isEmpty()) {
            return null;
        }
        try {
            URI redirected = endpoint.resolve(location);
            return "https".equals(redirected.getScheme())
                    && "auth.openai.com".equalsIgnoreCase(redirected.getHost())
                    && (redirected.getPort() == -1 || redirected.getPort() == 443)
                    && redirected.getRawUserInfo() == null
                    && redirected.getRawQuery() == null
                    && redirected.getFragment() == null
                    && ACCOUNTS_TOKEN_PATH.equals(redirected.getRawPath())
                    ? redirected : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String errorCode(HttpURLConnection response) {
        try (InputStream in = response.getErrorStream()) {
            if (in == null) {
                return "";
            }
            JSONObject data = new JSONObject(readBody(in, 8192));
            JSONObject error = data.optJSONObject("error");
            String code = error == null ? stringOrEmpty(data, "error") : stringOrEmpty(error, "code");
            return ERROR_CODE.matcher(code).matches() ? code : "";
        } catch (IOException | JSONException e) {
            return "";
        }
    }

    private static long retryAfter(HttpURLConnection response) {
        try {
            return Math.max(0, Math.min(MAX_DEVICE_SECONDS,
                    Long.parseLong(response.getHeaderField("Retry-After"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readBody(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (int count = in.read(buffer); count != -1; count = in.read(buffer)) {
            if (content.size() + count > limit) {
                throw new IOException("Unexpected response size");
            }
            content.write(buffer, 0, count);
        }
        return new String(content.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String stringOrEmpty(JSONObject data, String key) {
        Object value = data.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String params(String... pairs) {
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < pairs.length; index += 2) {
            if (index > 0) {
                encoded.append('&');
            }
            encoded.append(encode(pairs[index])).append('=').append(encode(pairs[index + 1]));
        }
        return encoded.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        final String code;
        final long retryAfterSeconds;

        HttpFailure(int status, String code, long retryAfterSeconds) {
            super("HTTP " + status);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    static final class DeviceCodeExpired extends IOException {
    }

    static final class DeviceCode {
        private final String id;
        final String userCode;
        private final long intervalSeconds;
        private final long expiresInSeconds;
        private final long createdAt = SystemClock.elapsedRealtime();

        DeviceCode(String id, String userCode, long intervalSeconds, long expiresInSeconds) {
            this.id = id;
            this.userCode = userCode;
            this.intervalSeconds = intervalSeconds;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    static final class DeviceGrant {
        final String code;
        final String verifier;

        DeviceGrant(String code, String verifier) {
            this.code = code;
            this.verifier = verifier;
        }
    }

    static final class Session {
        final String accessToken;
        final String refreshToken;
        final String accountId;
        final long expiresAt;

        Session(String accessToken, String refreshToken, String accountId, long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accountId = accountId;
            this.expiresAt = expiresAt;
        }

        boolean expiring(long now) {
            return expiresAt > 0 && expiresAt - now < 120_000;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("access", accessToken).put("refresh", refreshToken)
                    .put("account", accountId).put("expiresAt", expiresAt);
        }

        static Session fromStorage(JSONObject stored) throws JSONException {
            return new Session(stored.getString("access"), stored.getString("refresh"),
                    stored.getString("account"), stored.getLong("expiresAt"));
        }

        static Session fromTokenResponse(JSONObject response, Session previous) throws JSONException {
            String accessToken = response.getString("access_token");
            if (!BEARER.matcher(accessToken).matches()) {
                throw new JSONException("Invalid access token");
            }
            String refreshToken = stringOrEmpty(response, "refresh_token");
            if (refreshToken.isEmpty() && previous != null) {
                refreshToken = previous.refreshToken;
            }

            JSONObject accessClaims = jwtClaims(accessToken);
            JSONObject identityClaims = jwtClaims(stringOrEmpty(response, "id_token"));
            String account = accountId(identityClaims);
            if (account.isEmpty()) {
                account = accountId(accessClaims);
            }
            if (account.isEmpty() && previous != null) {
                account = previous.accountId;
            }

            long expiresAt = 0;
            long expiresIn = response.optLong("expires_in", 0);
            long exp = accessClaims.optLong("exp", 0);
            if (expiresIn > 0 && expiresIn < 31_536_000) {
                expiresAt = System.currentTimeMillis() + 1000 * expiresIn;
            } else if (exp > 0 && exp < Long.MAX_VALUE / 1000) {
                expiresAt = exp * 1000;
            }
            return new Session(accessToken, refreshToken, account, expiresAt);
        }

        private static String accountId(JSONObject claims) {
            // Routing metadata from the OAuth response. The server authorizes the bearer itself.
            JSONObject auth = claims.optJSONObject("https://api.openai.com/auth");
            String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
            return ACCOUNT_ID.matcher(account).matches() ? account : "";
        }

        private static JSONObject jwtClaims(String token) {
            String[] sections = token.split("\\.", -1);
            if (sections.length == 3) {
                try {
                    byte[] body = Base64.getUrlDecoder().decode(sections[1]);
                    return new JSONObject(new String(body, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException | JSONException ignored) {
                    // A valid access token can be opaque.
                }
            }
            return new JSONObject();
        }
    }
}

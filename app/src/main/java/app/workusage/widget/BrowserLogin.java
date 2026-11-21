package app.workusage.widget;

import android.net.Uri;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

final class BrowserLogin implements AutoCloseable {
    private static final int PRIMARY_PORT = 1455;
    private static final int FALLBACK_PORT = 1457;
    private static final int ATTEMPT_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int CALLBACK_TIMEOUT_MS = 2500;
    private static final int MAX_HEADERS = 16 * 1024;
    private static final String PATH = "/auth/callback";
    final String verifier = randomValue();
    private final String state = randomValue();
    private final ServerSocket listener;
    private final String authority;
    private Socket activeCallback;
    final String redirect;
    final String url;

    BrowserLogin() throws IOException {
        ServerSocket local;
        try {
            local = listen(PRIMARY_PORT);
        } catch (BindException occupied) {
            local = listen(FALLBACK_PORT);
        }
        listener = local;
        authority = "127.0.0.1:" + listener.getLocalPort();
        redirect = "http://" + authority + PATH;
        url = CodexClient.authorizeUrl(redirect, challenge(verifier), state);
    }

    private static ServerSocket listen(int port) throws IOException {
        ServerSocket local = new ServerSocket();
        try {
            local.setReuseAddress(true);
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            local.bind(new InetSocketAddress(loopback, port), 4);
            return local;
        } catch (IOException | RuntimeException e) {
            local.close();
            throw e;
        }
    }

    String awaitCode() throws IOException {
        long expiresAt = SystemClock.elapsedRealtime() + ATTEMPT_TIMEOUT_MS;
        while (!listener.isClosed()) {
            long remaining = expiresAt - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new SocketTimeoutException();
            }
            listener.setSoTimeout((int) remaining);
            Socket incoming = listener.accept();
            try (Socket socket = incoming) {
                synchronized (this) {
                    if (listener.isClosed()) {
                        return null;
                    }
                    activeCallback = socket;
                }
                Uri callback = parseCallback(readHeaders(socket, expiresAt), authority);
                if (callback == null || !validState(singleParameter(callback, "state"))) {
                    reply(socket, "400 Bad Request", "This sign-in request is no longer active.");
                    continue;
                }
                String code = singleParameter(callback, "code");
                String error = singleParameter(callback, "error");
                if (error != null && !error.isEmpty() && callback.getQueryParameters("code").isEmpty()) {
                    reply(socket, "400 Bad Request", "ChatGPT sign-in was cancelled.");
                    return null;
                }
                if (!CodexClient.validAuthorizationCode(code) || !callback.getQueryParameters("error").isEmpty()) {
                    reply(socket, "400 Bad Request", "OpenAI returned an invalid sign-in response.");
                    continue;
                }
                reply(socket, "200 OK", "Authorization received.");
                return code;
            } catch (IOException | IllegalArgumentException ignored) {
                // Browsers can preconnect or close a connection before a callback arrives.
            } finally {
                synchronized (this) {
                    activeCallback = null;
                }
            }
        }
        return null;
    }

    private static String readHeaders(Socket socket, long expiresAt) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        byte[] delimiter = new byte[]{'\r', '\n', '\r', '\n'};
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        long deadline = Math.min(expiresAt, SystemClock.elapsedRealtime() + CALLBACK_TIMEOUT_MS);
        int matched = 0;
        while (headers.size() < MAX_HEADERS) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                return null;
            }
            socket.setSoTimeout((int) remaining);
            int next = in.read();
            if (next == -1 || next > 127) {
                return null;
            }
            headers.write(next);
            if (next == delimiter[matched]) {
                matched++;
                if (matched == delimiter.length) {
                    return new String(headers.toByteArray(), StandardCharsets.US_ASCII);
                }
            } else {
                matched = next == delimiter[0] ? 1 : 0;
            }
        }
        return null;
    }

    static Uri parseCallback(String headers, String authority) {
        if (headers == null || !headers.endsWith("\r\n\r\n")) {
            return null;
        }
        String[] lines = headers.substring(0, headers.length() - 4).split("\r\n");
        String[] request = lines[0].split(" ", 3);
        if (request.length != 3 || !request[0].equals("GET")
                || !"HTTP/1.1".equals(request[2]) || !request[1].startsWith(PATH + "?")) {
            return null;
        }
        boolean hostMatches = false;
        for (int i = 1; i < lines.length; i++) {
            String header = lines[i];
            int colon = header.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(header.charAt(0))) {
                return null;
            }
            if ("Host".equalsIgnoreCase(header.substring(0, colon))) {
                if (hostMatches || !authority.equals(header.substring(colon + 1).trim())) {
                    return null;
                }
                hostMatches = true;
            }
        }
        try {
            URI target = URI.create(request[1]);
            return hostMatches && PATH.equals(target.getRawPath()) && target.getRawFragment() == null
                    ? Uri.parse(request[1]) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String singleParameter(Uri callback, String name) {
        List<String> values = callback.getQueryParameters(name);
        return values.size() == 1 ? values.get(0) : null;
    }

    private boolean validState(String returned) {
        return returned != null && returned.length() == state.length()
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                returned.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(Socket socket, String status, String message) {
        String html = "<!doctype html><html lang='en'><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Work Usage</title><style>"
                + "body{background:#10151c;color:#f1f7f6;font:16px system-ui;margin:12vh 7vw}"
                + "</style><h1>" + message + "</h1><p>Switch back to the Work Usage app to finish.</p></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'\r\n"
                + "Connection: close\r\n\r\n";
        try {
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // The user may already have switched back to the app.
        }
    }

    private static String challenge(String secret) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public synchronized void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        if (activeCallback != null) {
            try {
                activeCallback.close();
            } catch (IOException ignored) {
            }
        }
    }
}

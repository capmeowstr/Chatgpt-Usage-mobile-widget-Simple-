package app.workusage.widget;

import android.net.Uri;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

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
        redirect = "http://127.0.0.1:" + listener.getLocalPort() + PATH;
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
            Socket next = listener.accept();
            try (Socket socket = next) {
                socket.setSoTimeout(CALLBACK_TIMEOUT_MS);
                Uri redirect = readRedirect(socket.getInputStream());
                if (redirect == null || !validState(redirect.getQueryParameter("state"))) {
                    reply(socket, "400 Bad Request", "This sign-in request is no longer active.");
                    continue;
                }
                String code = redirect.getQueryParameter("code");
                if (code == null || code.isEmpty() || code.length() > 4096) {
                    reply(socket, "400 Bad Request", "ChatGPT sign-in was cancelled.");
                    return null;
                }
                reply(socket, "200 OK", "Authorization received.");
                return code;
            } catch (IOException | IllegalArgumentException ignored) {
                // Browsers can preconnect or close a connection before a callback arrives.
            }
        }
        return null;
    }

    private static Uri readRedirect(InputStream in) throws IOException {
        byte[] delimiter = new byte[]{'\r', '\n', '\r', '\n'};
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + CALLBACK_TIMEOUT_MS;
        int matched = 0;
        while (headers.size() < MAX_HEADERS && SystemClock.elapsedRealtime() < deadline) {
            int next = in.read();
            if (next == -1) {
                return null;
            }
            headers.write(next);
            if (next == delimiter[matched]) {
                matched++;
                if (matched == delimiter.length) {
                    break;
                }
            } else {
                matched = next == delimiter[0] ? 1 : 0;
            }
        }
        if (matched != delimiter.length) {
            return null;
        }
        String raw = new String(headers.toByteArray(), StandardCharsets.US_ASCII);
        String[] request = raw.substring(0, raw.indexOf("\r\n")).split(" ", 3);
        if (request.length != 3 || !request[0].equals("GET")
                || !request[1].startsWith(PATH + "?")) {
            return null;
        }
        return Uri.parse(request[1]);
    }

    private boolean validState(String returned) {
        return returned != null && MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                returned.getBytes(StandardCharsets.US_ASCII));
    }

    private static void reply(Socket socket, String status, String message) {
        String html = "<!doctype html><html lang='en'><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Work Usage</title><style>"
                + "body{background:#10151c;color:#f1f7f6;font:16px system-ui;margin:12vh 7vw}"
                + "a{display:inline-block;margin-top:12px;padding:14px 20px;"
                + "border-radius:12px;background:#a1f0cb;color:#152d28;"
                + "font-weight:650;text-decoration:none}"
                + "</style><h1>" + message + "</h1><p>Return to Work Usage to see your limits.</p>"
                + "<a href='intent://open#Intent;scheme=workusage;package=app.workusage.widget;end'>"
                + "Open Work Usage</a></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"
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
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
        }
    }
}

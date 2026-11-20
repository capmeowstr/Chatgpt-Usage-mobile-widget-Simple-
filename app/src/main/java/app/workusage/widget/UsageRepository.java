package app.workusage.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

final class UsageRepository {
    private static final String CACHE = "usage_snapshot";
    private static final String ERROR = "sync_error";
    private static final long REFRESH_DEBOUNCE_MS = 4500;
    private static UsageRepository instance;

    private final Context context;
    private final SharedPreferences state;
    private final TokenVault vault;
    private final CodexClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object signInVisibility = new Object();

    private volatile LoginAttempt pendingLogin;
    private volatile boolean syncing;
    private long lastAttempt;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private boolean signInVisible;

    private UsageRepository(Context context) {
        this.context = context.getApplicationContext();
        state = this.context.getSharedPreferences("usage_state", Context.MODE_PRIVATE);
        vault = new TokenVault(this.context);
        client = new CodexClient(new AndroidConnections(this.context));
    }

    static synchronized UsageRepository get(Context context) {
        if (instance == null) {
            instance = new UsageRepository(context);
        }
        return instance;
    }

    boolean connected() {
        return vault.exists();
    }

    boolean signingIn() {
        return pendingLogin != null;
    }

    boolean signingInWithDeviceCode() {
        LoginAttempt attempt = pendingLogin;
        return attempt != null && attempt.deviceCodeFlow;
    }

    String deviceUserCode() {
        LoginAttempt attempt = pendingLogin;
        CodexClient.DeviceCode code = attempt == null ? null : attempt.code;
        return code == null ? null : code.userCode;
    }

    boolean exchanging() {
        LoginAttempt attempt = pendingLogin;
        return attempt != null && attempt.exchanging;
    }

    boolean syncing() {
        return syncing;
    }

    UsageSnapshot snapshot() {
        return UsageSnapshot.fromCache(state.getString(CACHE, null));
    }

    String error() {
        return state.getString(ERROR, null);
    }

    void observe(Runnable refresh) {
        listeners.add(refresh);
    }

    void stopObserving(Runnable refresh) {
        listeners.remove(refresh);
    }

    void setSignInVisible(boolean visible) {
        synchronized (signInVisibility) {
            signInVisible = visible;
            signInVisibility.notifyAll();
        }
    }

    private void awaitSignInVisible(long deadline) throws InterruptedException {
        synchronized (signInVisibility) {
            while (!signInVisible) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    return;
                }
                signInVisibility.wait(remaining);
            }
        }
    }

    void startSignIn(boolean deviceCodeFlow, Consumer<String> openPage) {
        worker.execute(() -> {
            if (connected()) {
                return;
            }
            if (pendingLogin != null) {
                if (!deviceCodeFlow && !pendingLogin.exchanging && pendingLogin.browser != null) {
                    launchSignInPage(pendingLogin, openPage);
                }
                return;
            }
            LoginAttempt attempt = new LoginAttempt(deviceCodeFlow);
            pendingLogin = attempt;
            setError(null);
            changed();
            attempt.task = authWorker.submit(() -> completeSignIn(attempt, openPage));
        });
    }

    private void launchSignInPage(LoginAttempt attempt, Consumer<String> openPage) {
        main.post(() -> {
            if (pendingLogin == attempt && !attempt.exchanging) {
                BrowserLogin browser = attempt.browser;
                if (browser != null) {
                    openPage.accept(browser.url);
                }
            }
        });
    }

    private void completeSignIn(LoginAttempt attempt, Consumer<String> openPage) {
        try {
            CodexClient.Session session = attempt.deviceCodeFlow
                    ? finishDeviceSignIn(attempt) : finishBrowserSignIn(attempt, openPage);
            if (session != null) {
                worker.execute(() -> saveSignIn(attempt, session));
            }
        } catch (CodexClient.DeviceCodeExpired e) {
            failSignIn(attempt, "That device code expired. Get a new one.");
        } catch (CodexClient.HttpFailure e) {
            failSignIn(attempt, signInRejection(attempt, e));
        } catch (SocketTimeoutException e) {
            failSignIn(attempt, !attempt.deviceCodeFlow && !attempt.exchanging
                    ? "The browser didn’t return to the app. Try a device code."
                    : signInConnectionError(attempt, e));
        } catch (JSONException e) {
            failSignIn(attempt, "OpenAI sent an unexpected sign-in response. Use the usage dashboard if it recurs.");
        } catch (IOException e) {
            failSignIn(attempt, !attempt.deviceCodeFlow && attempt.browser == null
                    ? "This phone couldn’t open the browser callback. Use a device code."
                    : signInConnectionError(attempt, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CodexClient.Session finishDeviceSignIn(LoginAttempt attempt)
            throws IOException, JSONException, InterruptedException {
        CodexClient.DeviceCode code = client.requestDeviceCode();
        if (pendingLogin != attempt) {
            return null;
        }
        attempt.code = code;
        changed();
        CodexClient.DeviceGrant grant = client.awaitDeviceApproval(code, this::awaitSignInVisible);
        return exchange(attempt, grant.code, CodexClient.DEVICE_REDIRECT, grant.verifier);
    }

    private CodexClient.Session finishBrowserSignIn(LoginAttempt attempt, Consumer<String> openPage)
            throws IOException, JSONException, InterruptedException {
        try (BrowserLogin browser = new BrowserLogin()) {
            if (pendingLogin != attempt) {
                return null;
            }
            attempt.browser = browser;
            launchSignInPage(attempt, openPage);
            String code = browser.awaitCode();
            if (code == null) {
                failSignIn(attempt, "Browser sign-in was cancelled.");
                return null;
            }
            return exchange(attempt, code, browser.redirect, browser.verifier);
        }
    }

    private CodexClient.Session exchange(LoginAttempt attempt, String code, String redirect,
                                        String verifier)
            throws IOException, JSONException, InterruptedException {
        if (pendingLogin != attempt || Thread.currentThread().isInterrupted()) {
            return null;
        }
        attempt.exchanging = true;
        changed();
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        awaitSignInVisible(deadline);
        if (SystemClock.elapsedRealtime() >= deadline) {
            throw new SocketTimeoutException();
        }
        if (pendingLogin != attempt || Thread.currentThread().isInterrupted()) {
            return null;
        }
        return client.exchange(code, redirect, verifier);
    }

    private String signInConnectionError(LoginAttempt attempt, IOException error) {
        String step = attempt.exchanging ? "finish the approved sign-in"
                : attempt.code != null ? "check the browser approval" : "request a sign-in code";
        return "Couldn’t " + step + " (" + connectionReason(error) + ").";
    }

    private static String connectionReason(IOException error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return "network timeout";
            }
            if (cause instanceof SSLException) {
                return "TLS connection failed";
            }
            if (cause instanceof UnknownHostException) {
                return "DNS lookup failed";
            }
            if (cause instanceof ProtocolException || cause instanceof EOFException) {
                return "incomplete HTTP response";
            }
            if (cause instanceof ConnectException || cause instanceof SocketException) {
                return "network connection interrupted";
            }
            String platformError = AndroidConnections.networkError(cause);
            if (platformError != null) {
                return platformError;
            }
        }
        return "network I/O error";
    }

    private String signInRejection(LoginAttempt attempt, CodexClient.HttpFailure error) {
        String http = " (HTTP " + error.status + ").";
        if (attempt.deviceCodeFlow && ("device_auth_disabled".equals(error.code)
                || (attempt.code == null && (error.status == 403 || error.status == 404)))) {
            return "Device-code sign-in is unavailable" + http
                    + " Check ChatGPT Settings → Security or try browser sign-in.";
        }
        if ("invalid_grant".equals(error.code)) {
            return "OpenAI rejected the sign-in code" + http + " Start a new sign-in.";
        }
        if (error.status == 429) {
            return "OpenAI is rate-limiting sign-in" + http + " Try again in a few minutes.";
        }
        return "OpenAI rejected " + (attempt.exchanging ? "the sign-in exchange" : "the sign-in request") + http;
    }

    private void saveSignIn(LoginAttempt attempt, CodexClient.Session session) {
        if (pendingLogin != attempt) {
            return;
        }
        pendingLogin = null;
        try {
            vault.save(session);
            SharedPreferences.Editor updated = state.edit().remove(ERROR);
            UsageSnapshot saved = snapshot();
            if (saved == null || !saved.manuallyEntered) {
                updated.remove(CACHE);
            }
            updated.commit();
            UsageHistory.clearSynced(context);
            UsageAlerts.clearPending(context);
            lastAttempt = 0;
            SyncJob.schedulePeriodic(context);
            refreshNow(true);
        } catch (GeneralSecurityException | IOException | JSONException e) {
            setError("This phone couldn’t save the ChatGPT session. Try again.");
            changed();
        }
    }

    private void failSignIn(LoginAttempt attempt, String reason) {
        worker.execute(() -> {
            if (pendingLogin == attempt) {
                pendingLogin = null;
                setError(reason);
                changed();
            }
        });
    }

    void cancelSignIn(String message) {
        worker.execute(() -> {
            LoginAttempt attempt = pendingLogin;
            pendingLogin = null;
            if (attempt != null) {
                attempt.cancel();
            }
            setError(message);
            changed();
        });
    }

    void disconnect() {
        worker.execute(() -> {
            vault.clear();
            state.edit().remove(CACHE).remove(ERROR).commit();
            UsageHistory.clear(context);
            UsageAlerts.clearPending(context);
            lastAttempt = 0;
            SyncJob.cancel(context);
            changed();
        });
    }

    void saveManualUsage(Double fiveHours, Double week, Integer resets) {
        worker.execute(() -> {
            try {
                UsageSnapshot manual = UsageSnapshot.manual(fiveHours, week, resets,
                        System.currentTimeMillis());
                if (state.edit().putString(CACHE, manual.toJson().toString()).remove(ERROR).commit()) {
                    UsageHistory.record(context, manual);
                }
            } catch (JSONException e) {
                setError("Couldn’t save the entered usage.");
            }
            changed();
        });
    }

    void refresh(boolean force, Runnable onComplete) {
        worker.execute(() -> {
            try {
                refreshNow(force);
            } finally {
                if (onComplete != null) {
                    main.post(onComplete);
                }
            }
        });
    }

    private void refreshNow(boolean force) {
        if (!connected()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= lastAttempt && now - lastAttempt < REFRESH_DEBOUNCE_MS) {
            return;
        }
        UsageSnapshot saved = snapshot();
        if (!force && saved != null && now >= saved.fetchedAt && now - saved.fetchedAt < 60_000) {
            return;
        }
        lastAttempt = now;
        syncing = true;
        changed();
        try {
            CodexClient.Session session = vault.load();
            if (session == null) {
                return;
            }
            boolean renewed = session.expiring(now) && !session.refreshToken.isEmpty();
            if (renewed) {
                session = renew(session);
            }
            UsageSnapshot updated;
            try {
                updated = client.getSnapshot(session);
            } catch (CodexClient.HttpFailure error) {
                if (error.status != 401) {
                    throw error;
                }
                if (renewed || session.refreshToken.isEmpty()) {
                    throw new SessionExpired();
                }
                updated = client.getSnapshot(renew(session));
            }
            if (state.edit().putString(CACHE, updated.toJson().toString()).remove(ERROR).commit()) {
                UsageHistory.record(context, updated);
                UsageAlerts.check(context, updated);
            }
        } catch (SessionExpired | GeneralSecurityException e) {
            requireSignIn();
        } catch (CodexClient.HttpFailure e) {
            if (e.status == 401) {
                requireSignIn();
            } else if (e.status == 403) {
                setError("Usage access was denied for this ChatGPT workspace.");
            } else if (e.status == 429) {
                setError("ChatGPT is busy. Try refreshing in a little while.");
            } else {
                setError("ChatGPT usage is temporarily unavailable.");
            }
        } catch (JSONException e) {
            setError("ChatGPT returned usage data this app couldn’t read.");
        } catch (IOException e) {
            setError("Couldn’t reach ChatGPT. Check your connection and refresh.");
        } finally {
            syncing = false;
            changed();
        }
    }

    private CodexClient.Session renew(CodexClient.Session saved)
            throws IOException, JSONException, GeneralSecurityException, SessionExpired {
        CodexClient.Session renewed;
        try {
            renewed = client.renew(saved);
        } catch (CodexClient.HttpFailure error) {
            if (error.status == 400 || error.status == 401 || error.status == 403) {
                throw new SessionExpired();
            }
            throw error;
        }
        vault.save(renewed);
        return renewed;
    }

    private void requireSignIn() {
        vault.clear();
        SyncJob.cancel(context);
        UsageAlerts.clearPending(context);
        setError("Your ChatGPT session ended. Connect again to refresh.");
    }

    private void setError(String error) {
        if (error == null) {
            state.edit().remove(ERROR).apply();
        } else {
            state.edit().putString(ERROR, error).apply();
        }
    }

    private void changed() {
        main.post(() -> {
            UsageWidget.renderAll(context);
            for (Runnable listener : listeners.toArray(new Runnable[0])) {
                listener.run();
            }
        });
    }

    private static final class SessionExpired extends Exception {
    }

    private static final class LoginAttempt {
        final boolean deviceCodeFlow;
        volatile BrowserLogin browser;
        volatile CodexClient.DeviceCode code;
        volatile boolean exchanging;
        Future<?> task;

        LoginAttempt(boolean deviceCodeFlow) {
            this.deviceCodeFlow = deviceCodeFlow;
        }

        void cancel() {
            BrowserLogin active = browser;
            if (active != null) {
                active.close();
            }
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}

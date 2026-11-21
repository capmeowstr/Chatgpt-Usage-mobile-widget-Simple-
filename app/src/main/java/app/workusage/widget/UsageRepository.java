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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

final class UsageRepository {
    private static final String CACHE = "usage_snapshot";
    private static final String ERROR = "sync_error";
    private static final String ERASE_PENDING = "erase_pending";
    private static final String RETRY_AT = "sync_retry_at";
    private static final long REFRESH_DEBOUNCE_MS = 4500;
    private static UsageRepository instance;

    private final Context context;
    private final SharedPreferences state;
    private final TokenVault vault;
    private final CodexClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService privacyWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sessionLock = new Object();
    private final Object signInVisibility = new Object();

    private volatile LoginAttempt pendingLogin;
    private volatile long syncingGeneration = -1;
    private volatile boolean erasing;
    private volatile long sessionGeneration;
    private long lastAttempt;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private boolean signInVisible;

    private UsageRepository(Context context) {
        this.context = context.getApplicationContext();
        state = this.context.getSharedPreferences("usage_state", Context.MODE_PRIVATE);
        vault = new TokenVault(this.context);
        client = new CodexClient(new AndroidConnections(this.context));
        if (state.getBoolean(ERASE_PENDING, false)) {
            erasing = true;
            privacyWorker.execute(this::finishDisconnect);
        }
    }

    static synchronized UsageRepository get(Context context) {
        if (instance == null) {
            instance = new UsageRepository(context);
        }
        return instance;
    }

    boolean connected() {
        return !erasing && vault.exists();
    }

    boolean signingIn() {
        return pendingLogin != null;
    }

    boolean erasing() {
        return erasing;
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
        return !erasing && syncingGeneration == sessionGeneration;
    }

    UsageSnapshot snapshot() {
        synchronized (sessionLock) {
            UsageSnapshot saved = cachedSnapshot();
            return erasing || (saved != null && !saved.manuallyEntered && !connected()) ? null : saved;
        }
    }

    private UsageSnapshot cachedSnapshot() {
        return UsageSnapshot.fromCache(state.getString(CACHE, null));
    }

    boolean hasSavedUsage() {
        return !erasing && (state.contains(CACHE) || UsageHistory.hasSavedReadings(context));
    }

    List<UsageHistory.Point> history() {
        synchronized (sessionLock) {
            if (erasing) {
                return Collections.emptyList();
            }
            List<UsageHistory.Point> points = UsageHistory.recent(context, System.currentTimeMillis());
            if (!connected()) {
                points.removeIf(point -> !point.manual);
            }
            return points;
        }
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

    void startSignIn() {
        synchronized (sessionLock) {
            if (erasing || connected() || pendingLogin != null) {
                return;
            }
            LoginAttempt attempt = new LoginAttempt();
            pendingLogin = attempt;
            setError(null);
            attempt.task = authWorker.submit(() -> completeSignIn(attempt));
        }
        changed();
    }

    private void completeSignIn(LoginAttempt attempt) {
        try {
            CodexClient.Session session = finishDeviceSignIn(attempt);
            if (session != null) {
                worker.execute(() -> saveSignIn(attempt, session));
            }
        } catch (CodexClient.DeviceCodeExpired e) {
            failSignIn(attempt, "That device code expired. Get a new one.");
        } catch (CodexClient.HttpFailure e) {
            failSignIn(attempt, signInRejection(attempt, e));
        } catch (JSONException e) {
            failSignIn(attempt, "OpenAI sent an unexpected sign-in response. Use the usage dashboard if it recurs.");
        } catch (IOException e) {
            failSignIn(attempt, signInConnectionError(attempt, e));
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
        return client.exchange(grant.code, grant.verifier);
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
        if ("device_auth_disabled".equals(error.code)
                || (attempt.code == null && (error.status == 403 || error.status == 404))) {
            return "Device-code sign-in is unavailable" + http
                    + " Check ChatGPT Settings → Security or enter values manually.";
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
        long generation;
        synchronized (sessionLock) {
            if (pendingLogin != attempt || erasing) {
                return;
            }
            pendingLogin = null;
            try {
                if (!clearSyncedUsage(state.edit().remove(ERROR).remove(RETRY_AT))) {
                    throw new IOException("Could not clear previous usage");
                }
                vault.save(session);
                generation = ++sessionGeneration;
                lastAttempt = 0;
            } catch (GeneralSecurityException | IOException | JSONException e) {
                setError("This phone couldn’t save the ChatGPT session. Try again.");
                changed();
                return;
            }
        }
        changed();
        SyncJob.schedulePeriodic(context);
        refreshNow(generation, true);
    }

    private void failSignIn(LoginAttempt attempt, String reason) {
        synchronized (sessionLock) {
            if (pendingLogin != attempt) {
                return;
            }
            pendingLogin = null;
            setError(reason);
        }
        changed();
    }

    void cancelSignIn(String message) {
        LoginAttempt attempt;
        synchronized (sessionLock) {
            attempt = pendingLogin;
            pendingLogin = null;
            setError(message);
        }
        if (attempt != null) {
            attempt.cancel();
        }
        changed();
    }

    void disconnect() {
        LoginAttempt attempt;
        synchronized (sessionLock) {
            if (erasing) {
                return;
            }
            erasing = true;
            sessionGeneration++;
            attempt = pendingLogin;
            pendingLogin = null;
            state.edit().putBoolean(ERASE_PENDING, true).remove(ERROR).apply();
        }
        if (attempt != null) {
            attempt.cancel();
        }
        changed();
        privacyWorker.execute(this::finishDisconnect);
    }

    private void finishDisconnect() {
        state.edit().putBoolean(ERASE_PENDING, true).commit();
        SyncJob.cancel(context);
        boolean credentialsErased = vault.clear();
        UsageAlerts.clearPending(context);
        boolean historyErased = UsageHistory.clear(context);
        synchronized (sessionLock) {
            boolean cacheErased = state.edit().remove(CACHE).remove(ERROR).remove(RETRY_AT).commit();
            if (credentialsErased && historyErased && cacheErased
                    && state.edit().remove(ERASE_PENDING).commit()) {
                erasing = false;
                lastAttempt = 0;
            } else {
                state.edit().putBoolean(ERASE_PENDING, true).apply();
                setError("This phone couldn’t erase the saved data. Clear Work Usage storage in Android settings.");
            }
        }
        changed();
    }

    void saveManualUsage(Double fiveHours, Double week, Integer resets) {
        long generation = sessionGeneration;
        privacyWorker.execute(() -> {
            synchronized (sessionLock) {
                if (generation != sessionGeneration || erasing) {
                    return;
                }
                try {
                    UsageSnapshot manual = UsageSnapshot.manual(fiveHours, week, resets, System.currentTimeMillis());
                    if (state.edit().putString(CACHE, manual.toJson().toString()).remove(ERROR).commit()) {
                        UsageHistory.record(context, manual);
                    }
                } catch (JSONException e) {
                    setError("Couldn’t save the entered usage.");
                }
            }
            changed();
        });
    }

    void clearHistory() {
        privacyWorker.execute(() -> {
            UsageHistory.clear(context);
            changed();
        });
    }

    void refresh(boolean force, Runnable onComplete) {
        long generation = sessionGeneration;
        worker.execute(() -> {
            try {
                refreshNow(generation, force);
            } finally {
                if (onComplete != null) {
                    main.post(onComplete);
                }
            }
        });
    }

    private boolean active(long generation) {
        return generation == sessionGeneration && connected();
    }

    private void refreshNow(long generation, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (sessionLock) {
            if (!active(generation) || !readyToRefresh(now, force)) {
                return;
            }
            syncingGeneration = generation;
        }
        changed();
        try {
            saveUsage(generation, loadUsage(generation, now));
        } catch (SessionExpired | GeneralSecurityException e) {
            synchronized (sessionLock) {
                if (active(generation)) {
                    requireSignIn();
                }
            }
        } catch (IOException | JSONException e) {
            synchronized (sessionLock) {
                if (active(generation)) {
                    recordRefreshFailure(e);
                }
            }
        } finally {
            syncingGeneration = -1;
            changed();
        }
    }

    private void saveUsage(long generation, UsageSnapshot updated) throws JSONException {
        synchronized (sessionLock) {
            if (updated != null && active(generation)
                    && state.edit().putString(CACHE, updated.toJson().toString())
                            .remove(ERROR).remove(RETRY_AT).commit()) {
                UsageHistory.record(context, updated);
                UsageAlerts.check(context, updated);
            }
        }
    }

    private boolean readyToRefresh(long now, boolean force) {
        long elapsed = SystemClock.elapsedRealtime();
        long retryAt = state.getLong(RETRY_AT, 0);
        if (retryAt > now) {
            long maxWait = TimeUnit.SECONDS.toMillis(CodexClient.MAX_RETRY_SECONDS);
            if (retryAt - now > maxWait) {
                state.edit().putLong(RETRY_AT, now + maxWait).apply();
            }
            return false;
        }
        if (lastAttempt != 0 && elapsed - lastAttempt < REFRESH_DEBOUNCE_MS) {
            return false;
        }
        UsageSnapshot saved = cachedSnapshot();
        if (!force && saved != null && now >= saved.fetchedAt && now - saved.fetchedAt < 60_000) {
            return false;
        }
        lastAttempt = Math.max(1, elapsed);
        return true;
    }

    private UsageSnapshot loadUsage(long generation, long now)
            throws IOException, JSONException, GeneralSecurityException, SessionExpired {
        CodexClient.Session session;
        synchronized (sessionLock) {
            if (!active(generation)) {
                return null;
            }
            try {
                session = vault.load();
            } catch (JSONException | IOException unreadableSession) {
                throw new SessionExpired();
            }
        }
        if (session == null) {
            return null;
        }
        boolean renewed = session.expiring(now) && !session.refreshToken.isEmpty();
        if (renewed) {
            session = renew(session, generation);
        }
        try {
            return requestUsage(session, generation);
        } catch (CodexClient.HttpFailure error) {
            if (error.status != 401) {
                throw error;
            }
            if (renewed || session.refreshToken.isEmpty()) {
                throw new SessionExpired();
            }
            return requestUsage(renew(session, generation), generation);
        }
    }

    private UsageSnapshot requestUsage(CodexClient.Session session, long generation)
            throws IOException, JSONException {
        return session == null || !active(generation) ? null
                : client.getSnapshot(session, () -> active(generation));
    }

    private CodexClient.Session renew(CodexClient.Session saved, long generation)
            throws IOException, JSONException, GeneralSecurityException, SessionExpired {
        if (!active(generation)) {
            return null;
        }
        CodexClient.Session renewed;
        try {
            renewed = client.renew(saved);
        } catch (CodexClient.HttpFailure error) {
            if (error.status == 400 || error.status == 401 || error.status == 403) {
                throw new SessionExpired();
            }
            throw error;
        }
        synchronized (sessionLock) {
            if (!active(generation)) {
                return null;
            }
            if (!saved.accountId.isEmpty() && !saved.accountId.equals(renewed.accountId)) {
                throw new SessionExpired();
            }
            vault.save(renewed);
            return renewed;
        }
    }

    private void recordRefreshFailure(Exception error) {
        if (error instanceof CodexClient.HttpFailure) {
            CodexClient.HttpFailure http = (CodexClient.HttpFailure) error;
            if (http.status == 401) {
                requireSignIn();
            } else if (http.status == 403) {
                setError("Usage access was denied for this ChatGPT workspace.");
            } else if (http.status == 429) {
                long delay = Math.max(CodexClient.MIN_RATE_LIMIT_SECONDS, http.retryAfterSeconds);
                state.edit()
                        .putLong(RETRY_AT, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delay))
                        .putString(ERROR, "ChatGPT has paused usage checks. Try refreshing later.")
                        .apply();
            } else {
                setError("ChatGPT usage is temporarily unavailable.");
            }
        } else {
            setError(error instanceof JSONException ? "ChatGPT returned usage data this app couldn’t read."
                    : "Couldn’t reach ChatGPT. Check your connection and refresh.");
        }
    }

    private void requireSignIn() {
        boolean cleared = vault.clear();
        sessionGeneration++;
        boolean usageCleared = clearSyncedUsage(state.edit().remove(RETRY_AT));
        SyncJob.cancel(context);
        setError(cleared && usageCleared ? "Your saved ChatGPT session is unavailable. Connect again to refresh."
                : "This phone couldn’t erase saved account data. Clear Work Usage storage in Android settings.");
    }

    private boolean clearSyncedUsage(SharedPreferences.Editor editor) {
        UsageSnapshot saved = cachedSnapshot();
        if (saved == null || !saved.manuallyEntered) {
            editor.remove(CACHE);
        }
        boolean cacheCleared = editor.commit();
        boolean historyCleared = UsageHistory.clearSynced(context);
        UsageAlerts.clearPending(context);
        return cacheCleared && historyCleared;
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
        volatile CodexClient.DeviceCode code;
        volatile boolean exchanging;
        Future<?> task;

        void cancel() {
            task.cancel(true);
        }
    }
}

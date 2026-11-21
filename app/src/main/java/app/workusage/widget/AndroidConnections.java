package app.workusage.widget;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.NetworkException;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;

final class AndroidConnections implements CodexClient.Connections {
    private final Context context;
    private CodexClient.Connections transport;

    AndroidConnections(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public HttpURLConnection open(URI endpoint) throws IOException {
        return transport().open(endpoint);
    }

    private synchronized CodexClient.Connections transport() {
        if (transport == null) {
            transport = AndroidConnections::openSystemConnection;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    transport = PlatformHttp.create(context);
                } catch (RuntimeException | LinkageError unavailable) {
                    // Some Android builds lack a functioning platform HTTP engine module.
                }
            }
        }
        return transport;
    }

    private static HttpURLConnection openSystemConnection(URI endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        if ("auth.openai.com".equals(endpoint.getHost())) {
            connection.setRequestProperty("Connection", "close");
        }
        return connection;
    }

    static String networkError(Throwable cause) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return PlatformHttp.networkError(cause);
            } catch (LinkageError unavailable) {
                // Older builds can be missing the platform HTTP module.
            }
        }
        return null;
    }

    private static final class PlatformHttp {
        static CodexClient.Connections create(Context context) {
            HttpEngine engine = new HttpEngine.Builder(context)
                    .setEnableQuic(false)
                    .setEnableHttp2(true)
                    .build();
            return endpoint -> (HttpURLConnection) engine.openConnection(endpoint.toURL());
        }

        static String networkError(Throwable cause) {
            if (!(cause instanceof NetworkException)) {
                return null;
            }
            int code = ((NetworkException) cause).getErrorCode();
            return String.format(Locale.ROOT, "Android network error %d", code);
        }
    }
}

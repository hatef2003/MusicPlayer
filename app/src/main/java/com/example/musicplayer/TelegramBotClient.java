package com.example.musicplayer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lightweight Telegram Bot API client.
 *
 * <p>Threading: API calls (getMe/getUpdates/getChat/getFile) run on one serial
 * executor so their ordering is preserved, while file downloads run on a separate
 * small pool. A long download therefore never blocks a sync or a getFile lookup,
 * and several tracks can download at once. UI callbacks are posted to the main
 * thread, except {@code onComplete} of {@link #downloadFile} which receives the
 * stream on the download thread (reading it is network I/O).</p>
 *
 * <p>Security: the bot token is part of every request URL, so it is redacted from
 * every error message and log line this class produces. Plain-HTTP base URLs are
 * only accepted for loopback / private-network hosts (a self-hosted Local Bot API
 * server); sending the token in cleartext over the internet is refused.</p>
 */
public class TelegramBotClient {

    private static final String TAG = "TelegramBotClient";
    private static final String DEFAULT_BASE_URL = "https://api.telegram.org";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;
    /** Extra read-timeout headroom on top of the long-poll duration. */
    private static final int LONG_POLL_SLACK_MS = 10_000;
    private static final int MAX_PARALLEL_DOWNLOADS = 3;

    private final String botToken;
    private final String baseUrl;
    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor(named("tg-api"));
    private final ExecutorService downloadExecutor =
            Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS, named("tg-download"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public TelegramBotClient(String botToken, String baseUrl) {
        this.botToken = normalizeToken(botToken);
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    /**
     * Canonical form of a base URL as typed in Settings: trimmed, no trailing
     * slashes, default when blank. Callers comparing configs must use this, or
     * "https://host/" vs "https://host" looks like a different bot.
     */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return DEFAULT_BASE_URL;
        String url = baseUrl.trim().replaceAll("/+$", "");
        // Tolerate a pasted ".../bot" suffix — we append "/bot<token>" ourselves.
        if (url.endsWith("/bot")) url = url.substring(0, url.length() - 4);
        return url.isEmpty() ? DEFAULT_BASE_URL : url;
    }

    /** Canonical form of a bot token: trimmed, and an accidental "bot" prefix removed. */
    public static String normalizeToken(String token) {
        if (token == null) return "";
        String t = token.trim();
        // Some guides show URLs like /bot123:ABC — people paste "bot123:ABC".
        if (t.startsWith("bot") && t.length() > 3 && Character.isDigit(t.charAt(3))) t = t.substring(3);
        return t;
    }

    public TelegramBotClient(String botToken) {
        this(botToken, DEFAULT_BASE_URL);
    }

    // ── Public API ──────────────────────────────────────────

    /** Test bot connectivity. Calls onResult(true) on success, onError on failure. */
    public void getMe(Consumer<Boolean> onResult, Consumer<String> onError) {
        apiExecutor.execute(() -> {
            try {
                JSONObject json = call("getMe");
                if (isOk(json)) {
                    JSONObject result = json.optJSONObject("result");
                    String name = result != null ? result.optString("first_name", "") : "";
                    Log.d(TAG, "Bot connected: " + name);
                    post(() -> onResult.accept(true));
                } else {
                    String desc = describe(json, "Unknown error");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                fail("getMe", e, onError);
            }
        });
    }

    /**
     * Poll getUpdates. Returns a JSON array of Update objects.
     * offset: the last update_id + 1, or 0 to start fresh.
     * timeout: long-poll seconds (0 = short poll).
     */
    public void getUpdates(long offset, int timeout, Consumer<JSONArray> onResult, Consumer<String> onError) {
        apiExecutor.execute(() -> {
            try {
                String url = methodUrl("getUpdates")
                        + "?offset=" + offset
                        + "&timeout=" + Math.max(0, timeout)
                        + "&allowed_updates=" + URLEncoder.encode(
                            "[\"message\",\"my_chat_member\"]", "UTF-8");
                // A long poll legitimately holds the connection for `timeout`
                // seconds, so the read timeout must outlast it.
                int readTimeout = Math.max(READ_TIMEOUT, timeout * 1000 + LONG_POLL_SLACK_MS);
                JSONObject json = httpGet(url, readTimeout);
                if (isOk(json)) {
                    JSONArray arr = json.optJSONArray("result");
                    post(() -> onResult.accept(arr != null ? arr : new JSONArray()));
                } else {
                    String desc = describe(json, "getUpdates failed");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                fail("getUpdates", e, onError);
            }
        });
    }

    /** Get chat info (title, type, etc). */
    public void getChat(String chatId, Consumer<JSONObject> onResult, Consumer<String> onError) {
        apiExecutor.execute(() -> {
            try {
                JSONObject json = call("getChat", "chat_id", chatId);
                if (isOk(json)) {
                    JSONObject result = json.optJSONObject("result");
                    post(() -> onResult.accept(result));
                } else {
                    String desc = describe(json, "getChat failed");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                fail("getChat", e, onError);
            }
        });
    }

    /** Get a file path for download (Bot API getFile). */
    public void getFilePath(String fileId, Consumer<JSONObject> onResult, Consumer<String> onError) {
        apiExecutor.execute(() -> {
            try {
                JSONObject json = call("getFile", "file_id", fileId);
                if (isOk(json)) {
                    JSONObject result = json.optJSONObject("result");
                    post(() -> onResult.accept(result));
                } else {
                    String desc = describe(json, "getFile failed");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                fail("getFile", e, onError);
            }
        });
    }

    /**
     * Download a file from Telegram servers on the download pool.
     * @param filePath The file_path returned by getFile
     * @param onProgress called with (bytesRead, totalBytes) on the download thread.
     *                   totalBytes may be -1 if unknown. Throttled to ~1% steps.
     * @param onComplete called on the download thread with the InputStream; the
     *                   connection is closed after it returns, so consume the stream
     *                   synchronously inside the callback.
     * @param onError called on the main thread with an error message.
     */
    public void downloadFile(String filePath, FileProgressCallback onProgress,
                             Consumer<InputStream> onComplete, Consumer<String> onError) {
        downloadExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                ensureTransportAllowed();
                String url = baseUrl + "/file/bot" + botToken + "/" + filePath;
                conn = open(url, READ_TIMEOUT);

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    final int code = responseCode;
                    post(() -> onError.accept("HTTP " + code));
                    return;
                }

                long totalBytes = conn.getContentLengthLong(); // may be -1
                try (InputStream raw = conn.getInputStream();
                     ProgressInputStream pis = new ProgressInputStream(raw, totalBytes, onProgress)) {
                    onComplete.accept(pis);
                }
            } catch (Exception e) {
                fail("downloadFile", e, onError);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Stop all background work. Queued requests are dropped; in-flight ones are interrupted. */
    public void shutdown() {
        apiExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
    }

    public String getBotToken() { return botToken; }
    public String getBaseUrl() { return baseUrl; }

    /** True when the default (cloud) Bot API is in use, which caps downloads at 20 MB. */
    public boolean isOfficialApi() { return DEFAULT_BASE_URL.equals(baseUrl); }

    // ── Internal helpers ────────────────────────────────────

    private String methodUrl(String method) {
        return baseUrl + "/bot" + botToken + "/" + method;
    }

    private JSONObject call(String method, String... keyValuePairs) throws Exception {
        StringBuilder sb = new StringBuilder(methodUrl(method));
        if (keyValuePairs.length > 0) {
            sb.append("?");
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                if (i > 0) sb.append("&");
                sb.append(keyValuePairs[i]).append("=")
                  .append(URLEncoder.encode(keyValuePairs[i + 1], "UTF-8"));
            }
        }
        return httpGet(sb.toString(), READ_TIMEOUT);
    }

    private JSONObject httpGet(String urlStr, int readTimeout) throws Exception {
        ensureTransportAllowed();
        HttpURLConnection conn = open(urlStr, readTimeout);
        try {
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new IOException("HTTP " + code + " (empty response)");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            try {
                return new JSONObject(sb.toString());
            } catch (Exception parse) {
                // e.g. an HTML error page from a proxy / misconfigured base URL
                throw new IOException("HTTP " + code + ": response is not JSON");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(readTimeout);
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        return conn;
    }

    /**
     * Refuse to send the bot token over plain HTTP unless the server is on this
     * device or the local network (the self-hosted Local Bot API server case).
     */
    private void ensureTransportAllowed() throws IOException {
        URL url = new URL(baseUrl);
        String protocol = url.getProtocol();
        if ("https".equalsIgnoreCase(protocol)) return;
        if (!"http".equalsIgnoreCase(protocol)) {
            throw new IOException("Unsupported base URL scheme: " + protocol);
        }
        InetAddress addr = InetAddress.getByName(url.getHost());
        boolean local = addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress();
        if (!local) {
            throw new IOException("Refusing to send the bot token over plain HTTP to "
                    + url.getHost() + ". Use https:// (http:// is only allowed for a "
                    + "Local Bot API server on your own network).");
        }
    }

    private static boolean isOk(JSONObject json) {
        return json.optBoolean("ok", false);
    }

    private String describe(JSONObject json, String fallback) {
        return redact(json.optString("description", fallback));
    }

    private void fail(String what, Exception e, Consumer<String> onError) {
        String msg = redact(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        // Log without the exception object: its message/stack may carry the token-bearing URL.
        Log.e(TAG, what + " failed: " + e.getClass().getSimpleName() + ": " + msg);
        post(() -> onError.accept(msg));
    }

    /** Strip the bot token from any text that may have picked it up from a URL. */
    private String redact(String text) {
        if (text == null) return "";
        if (botToken.isEmpty()) return text;
        return text.replace(botToken, "<token>");
    }

    private void post(Runnable r) {
        mainHandler.post(r);
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Callback for download progress. */
    public interface FileProgressCallback {
        void onProgress(long bytesRead, long totalBytes);
    }

    /** InputStream wrapper that reports byte-level progress, throttled to avoid flooding the UI. */
    private static class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final long totalBytes;
        private final FileProgressCallback callback;
        private final long step;
        private long bytesRead = 0;
        private long lastReported = -1;

        ProgressInputStream(InputStream delegate, long totalBytes, FileProgressCallback callback) {
            this.delegate = delegate;
            this.totalBytes = totalBytes;
            this.callback = callback;
            // Report every ~1% of a known size, or every 64 KB when the size is unknown.
            this.step = totalBytes > 0 ? Math.max(totalBytes / 100, 8 * 1024) : 64 * 1024;
        }

        private void report(boolean force) {
            if (callback == null) return;
            if (force || bytesRead - lastReported >= step) {
                lastReported = bytesRead;
                callback.onProgress(bytesRead, totalBytes);
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                bytesRead++;
                report(false);
            } else {
                report(true);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                bytesRead += n;
                report(false);
            } else if (n < 0) {
                report(true);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}

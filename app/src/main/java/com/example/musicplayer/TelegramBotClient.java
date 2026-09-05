package com.example.musicplayer;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lightweight Telegram Bot API client.
 * All network calls run on a background thread; UI callbacks are posted to the main thread.
 */
public class TelegramBotClient {

    private static final String TAG = "TelegramBotClient";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private final String botToken;
    private final String baseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public TelegramBotClient(String botToken, String baseUrl) {
        this.botToken = botToken != null ? botToken.trim() : "";
        this.baseUrl = baseUrl != null && !baseUrl.trim().isEmpty()
                ? baseUrl.trim().replaceAll("/+$", "")
                : "https://api.telegram.org";
    }

    public TelegramBotClient(String botToken) {
        this(botToken, "https://api.telegram.org");
    }

    // ── Public API ──────────────────────────────────────────

    /** Test bot connectivity. Calls onResult(true) on success, onResult(false) + onError on failure. */
    public void getMe(Consumer<Boolean> onResult, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                JSONObject json = call("getMe");
                if (json.has("ok") && json.getBoolean("ok")) {
                    JSONObject result = json.optJSONObject("result");
                    String name = result != null ? result.optString("first_name", "") : "";
                    Log.d(TAG, "Bot connected: " + name);
                    post(() -> onResult.accept(true));
                } else {
                    String desc = json.optString("description", "Unknown error");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                Log.e(TAG, "getMe failed", e);
                post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /**
     * Long-poll getUpdates. Returns a JSON array of Update objects.
     * offset: the last update_id + 1, or 0 to start fresh.
     * timeout: long-poll seconds (0 = short poll).
     */
    public void getUpdates(long offset, int timeout, Consumer<JSONArray> onResult, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                String url = baseUrl + "/bot" + botToken + "/getUpdates"
                        + "?offset=" + offset
                        + "&timeout=" + timeout
                        + "&allowed_updates=" + java.net.URLEncoder.encode(
                            "[\"message\",\"my_chat_member\"]", "UTF-8");
                JSONObject json = httpGet(url);
                if (json.has("ok") && json.getBoolean("ok")) {
                    JSONArray arr = json.optJSONArray("result");
                    post(() -> onResult.accept(arr != null ? arr : new JSONArray()));
                } else {
                    String desc = json.optString("description", "getUpdates failed");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                Log.e(TAG, "getUpdates failed", e);
                post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /** Get chat info (title, type, etc). */
    public void getChat(String chatId, Consumer<JSONObject> onResult, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                JSONObject json = call("getChat", "chat_id", chatId);
                if (json.has("ok") && json.getBoolean("ok")) {
                    post(() -> onResult.accept(json.optJSONObject("result")));
                } else {
                    post(() -> onError.accept(json.optString("description", "getChat failed")));
                }
            } catch (Exception e) {
                post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /** Get a file path for download (Bot API getFile). */
    public void getFilePath(String fileId, Consumer<JSONObject> onResult, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                JSONObject json = call("getFile", "file_id", fileId);
                if (json.has("ok") && json.getBoolean("ok")) {
                    JSONObject result = json.optJSONObject("result");
                    post(() -> onResult.accept(result));
                } else {
                    String desc = json.optString("description", "getFile failed");
                    post(() -> onError.accept(desc));
                }
            } catch (Exception e) {
                post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /**
     * Download a file from Telegram servers.
     * @param filePath The file_path returned by getFile
     * @param onProgress called with (bytesRead, totalBytes). totalBytes may be -1 if unknown.
     * @param onComplete called with the InputStream (caller must close it) or null on error.
     * @param onError called with error message.
     */
    public void downloadFile(String filePath, FileProgressCallback onProgress,
                             Consumer<InputStream> onComplete, Consumer<String> onError) {
        executor.execute(() -> {
            try {
                String url = baseUrl + "/file/bot" + botToken + "/" + filePath;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    final int code = responseCode;
                    post(() -> onError.accept("HTTP " + code));
                    return;
                }

                int totalBytes = conn.getContentLength(); // may be -1
                InputStream rawStream = conn.getInputStream();

                // Wrap in a progress-reporting stream
                ProgressInputStream pis = new ProgressInputStream(rawStream, totalBytes, onProgress);
                // Run onComplete on background thread — InputStream read is network I/O
                onComplete.accept(pis);
            } catch (Exception e) {
                Log.e(TAG, "downloadFile failed", e);
                post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    public String getBotToken() { return botToken; }
    public String getBaseUrl() { return baseUrl; }

    // ── Internal helpers ────────────────────────────────────

    private JSONObject call(String method, String... keyValuePairs) throws Exception {
        StringBuilder sb = new StringBuilder(baseUrl)
                .append("/bot").append(botToken).append("/").append(method);
        if (keyValuePairs.length > 0) {
            sb.append("?");
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                if (i > 0) sb.append("&");
                sb.append(keyValuePairs[i]).append("=")
                  .append(java.net.URLEncoder.encode(keyValuePairs[i + 1], "UTF-8"));
            }
        }
        return httpGet(sb.toString());
    }

    private JSONObject httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        return new JSONObject(sb.toString());
    }

    private void post(Runnable r) {
        mainHandler.post(r);
    }

    /** Callback for download progress. */
    public interface FileProgressCallback {
        void onProgress(long bytesRead, long totalBytes);
    }

    /** InputStream wrapper that reports byte-level progress. */
    private static class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final long totalBytes;
        private final FileProgressCallback callback;
        private long bytesRead = 0;

        ProgressInputStream(InputStream delegate, long totalBytes, FileProgressCallback callback) {
            this.delegate = delegate;
            this.totalBytes = totalBytes;
            this.callback = callback;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = delegate.read();
            if (b >= 0) {
                bytesRead++;
                if (callback != null && bytesRead % 4096 == 0) {
                    callback.onProgress(bytesRead, totalBytes);
                }
            } else if (callback != null) {
                callback.onProgress(bytesRead, totalBytes);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                bytesRead += n;
                if (callback != null) {
                    callback.onProgress(bytesRead, totalBytes);
                }
            } else if (n < 0 && callback != null) {
                callback.onProgress(bytesRead, totalBytes);
            }
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            delegate.close();
        }
    }
}

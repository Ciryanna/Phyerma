package de.rwth_aachen.phyphox.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

//Minimal client for DeepSeek's OpenAI-compatible chat endpoint. One blocking call per turn, so call
//it off the main thread.
public final class DeepSeekClient {
    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    //"DeepSeek-V4.1-Flash" in DeepSeek's model list
    public static final String MODEL = "deepseek-flash";
    private static final int MAX_TOKENS = 8000;

    private DeepSeekClient() {}

    //An error answer from the service (or a reply that cannot be read). status is the HTTP status
    //or 0 if there was none.
    public static class ApiException extends IOException {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    //Thinking is switched off on purpose: with it on, the model spends the whole token budget on
    //reasoning for such a task and takes minutes; without it an experiment arrives in seconds.
    static String requestBody(JSONArray messages) throws JSONException {
        return new JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("stream", false)
                .put("max_tokens", MAX_TOKENS)
                .put("thinking", new JSONObject().put("type", "disabled"))
                .toString();
    }

    //The text of the model's answer. A reply cut off by the token limit is returned as it is; the
    //caller can see that from the unterminated code block.
    static String parseResponse(int status, String body) throws ApiException {
        if (status != 200)
            throw new ApiException(status, errorText(body));
        try {
            JSONObject message = new JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            return message.optString("content", "");
        } catch (JSONException e) {
            throw new ApiException(status, "Unreadable answer from the service.");
        }
    }

    private static String errorText(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty())
                return error.optString("message");
        } catch (JSONException ignored) {
        }
        return body == null || body.isEmpty() ? "No answer." : body;
    }

    public static String chat(String apiKey, JSONArray messages) throws IOException {
        byte[] payload;
        try {
            payload = requestBody(messages).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            return parseResponse(status, in == null ? "" : readAll(in));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) != -1)
                buffer.write(chunk, 0, n);
            return buffer.toString("UTF-8");
        }
    }
}

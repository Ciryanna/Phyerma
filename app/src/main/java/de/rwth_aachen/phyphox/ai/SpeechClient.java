package de.rwth_aachen.phyphox.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

//Speech to text for phones that have no system recognizer: the recording is sent to an
//OpenAI-compatible transcription endpoint (SiliconFlow's free SenseVoice model by default) and the
//text comes back. One blocking call, so call it off the main thread.
public final class SpeechClient {
    public static final String ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions";
    public static final String MODEL = "FunAudioLLM/SenseVoiceSmall";
    public static final int SAMPLE_RATE = 16000;

    private SpeechClient() {}

    //16-bit mono PCM in a WAV container, which every transcription service accepts
    static byte[] wav(short[] pcm, int length, int rate) {
        int dataBytes = length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataBytes);
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataBytes);
        writeAscii(out, "WAVEfmt ");
        writeInt(out, 16);
        writeShort(out, 1);          //PCM
        writeShort(out, 1);          //mono
        writeInt(out, rate);
        writeInt(out, rate * 2);     //bytes per second
        writeShort(out, 2);          //bytes per frame
        writeShort(out, 16);         //bits per sample
        writeAscii(out, "data");
        writeInt(out, dataBytes);
        for (int i = 0; i < length; i++)
            writeShort(out, pcm[i]);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int shift = 0; shift < 32; shift += 8)
            out.write((value >> shift) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    static byte[] multipartBody(String boundary, byte[] wav) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + MODEL + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    //The recognized text, or an ApiException carrying the service's message
    static String parseResponse(int status, String body) throws DeepSeekClient.ApiException {
        try {
            JSONObject json = new JSONObject(body);
            if (status == 200)
                return json.optString("text", "").trim();
            String message = json.optString("message");
            JSONObject error = json.optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty())
                message = error.optString("message");
            throw new DeepSeekClient.ApiException(status, message.isEmpty() ? body : message);
        } catch (JSONException e) {
            throw new DeepSeekClient.ApiException(status, status == 200 ? "Unreadable answer from the service."
                    : (body == null || body.isEmpty() ? "No answer." : body));
        }
    }

    public static String transcribe(String apiKey, byte[] wav) throws IOException {
        return transcribe(ENDPOINT, apiKey, wav);
    }

    static String transcribe(String endpoint, String apiKey, byte[] wav) throws IOException {
        String boundary = "phyerma" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = multipartBody(boundary, wav);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
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

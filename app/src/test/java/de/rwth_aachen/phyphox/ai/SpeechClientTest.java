package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SpeechClientTest {

    @Test
    public void wavHasAValidHeaderAndLittleEndianSamples() {
        byte[] wav = SpeechClient.wav(new short[]{1, -2, 3, 99}, 3, 16000);
        assertEquals(44 + 6, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVEfmt ", new String(wav, 8, 8, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(6, wav[40]);
        assertEquals(36 + 6, wav[4]);
        assertEquals(16000 & 0xff, wav[24] & 0xff);
        assertEquals(1, wav[44]);
        assertEquals((byte) 0xfe, wav[46]);
        assertEquals((byte) 0xff, wav[47]);
    }

    @Test
    public void textIsExtracted() throws Exception {
        assertEquals("你好", SpeechClient.parseResponse(200, "{\"text\":\" 你好 \"}"));
    }

    @Test
    public void errorsCarryTheServiceMessage() {
        try {
            SpeechClient.parseResponse(401, "{\"code\":20012,\"message\":\"Invalid token\"}");
            fail("expected an ApiException");
        } catch (DeepSeekClient.ApiException e) {
            assertEquals(401, e.status);
            assertEquals("Invalid token", e.getMessage());
        }
    }

    @Test
    public void garbageIsAnErrorNotACrash() {
        try {
            SpeechClient.parseResponse(200, "<html>proxy</html>");
            fail("expected an ApiException");
        } catch (DeepSeekClient.ApiException e) {
            assertEquals(200, e.status);
        }
    }

    //Just enough of an HTTP server for one request: the request's header block and body come back
    //through the references, and the canned answer is returned.
    private static Thread serve(ServerSocket socket, AtomicReference<String> head, AtomicReference<byte[]> body) {
        Thread thread = new Thread(() -> {
            try (Socket client = socket.accept()) {
                InputStream in = client.getInputStream();
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                while (!header.toString("ISO-8859-1").endsWith("\r\n\r\n"))
                    header.write(in.read());
                String text = header.toString("ISO-8859-1");
                head.set(text);
                int length = 0;
                for (String line : text.split("\r\n"))
                    if (line.toLowerCase().startsWith("content-length:"))
                        length = Integer.parseInt(line.substring(15).trim());
                byte[] data = new byte[length];
                int got = 0;
                while (got < length)
                    got += in.read(data, got, length - got);
                body.set(data);
                byte[] answer = "{\"text\":\"measure the pendulum\"}".getBytes(StandardCharsets.UTF_8);
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + answer.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(answer);
                out.flush();
            } catch (Exception ignored) {
            }
        });
        thread.start();
        return thread;
    }

    @Test
    public void uploadIsAMultipartFormWithTheModelAndTheAudio() throws Exception {
        AtomicReference<String> head = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread server = serve(socket, head, body);
            byte[] wav = SpeechClient.wav(new short[]{5, 6, 7}, 3, 16000);
            String text = SpeechClient.transcribe("http://127.0.0.1:" + socket.getLocalPort()
                    + "/v1/audio/transcriptions", "secret", wav);
            server.join(5000);
            assertEquals("measure the pendulum", text);
            assertTrue(head.get(), head.get().startsWith("POST /v1/audio/transcriptions"));
            assertTrue(head.get(), head.get().contains("Authorization: Bearer secret"));
            assertTrue(head.get(), head.get().contains("multipart/form-data; boundary="));
            String sent = new String(body.get(), StandardCharsets.ISO_8859_1);
            assertTrue(sent.contains("name=\"model\"\r\n\r\n" + SpeechClient.MODEL));
            assertTrue(sent.contains("name=\"file\"; filename=\"speech.wav\""));
            assertTrue(sent.contains("RIFF"));
        }
    }
}

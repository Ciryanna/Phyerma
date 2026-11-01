package de.rwth_aachen.phyphox.ai;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Arrays;

//Records the microphone as 16 kHz mono PCM until stop() or the length limit. The caller needs the
//RECORD_AUDIO permission before start().
final class VoiceRecorder {
    static final int MAX_SECONDS = 60;

    interface Done {
        //Called on the recorder's thread; pcm is null if the microphone could not be opened
        void onRecorded(short[] pcm);
    }

    private volatile boolean running;
    private Thread thread;

    boolean recording() {
        return running;
    }

    @SuppressLint("MissingPermission")
    void start(Done done) {
        running = true;
        thread = new Thread(() -> done.onRecorded(record()), "voice-recorder");
        thread.start();
    }

    void stop() {
        running = false;
    }

    @SuppressLint("MissingPermission")
    private short[] record() {
        int rate = SpeechClient.SAMPLE_RATE;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            running = false;
            return null;
        }
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                running = false;
                return null;
            }
            short[] all = new short[rate * MAX_SECONDS];
            short[] chunk = new short[2048];
            int length = 0;
            recorder.startRecording();
            while (running && length < all.length) {
                int n = recorder.read(chunk, 0, Math.min(chunk.length, all.length - length));
                if (n < 0)
                    break;
                System.arraycopy(chunk, 0, all, length, n);
                length += n;
            }
            recorder.stop();
            running = false;
            return Arrays.copyOf(all, length);
        } catch (RuntimeException e) {
            running = false;
            return null;
        } finally {
            if (recorder != null)
                recorder.release();
        }
    }
}

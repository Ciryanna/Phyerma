package de.rwth_aachen.phyphox;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//SyncAudioTrack records an optional, experiment-independent audio track whose sole purpose is
// acoustic synchronization between phones: a loud transient (a clap, a knock) appears in every
// recording and lets offline tooling align the experiment-time axes of several devices.
//
//Two capture modes:
// - shared: the experiment declares an <audio> input and already owns an AudioRecord. We tap the
//   samples at the read() call site in PhyphoxExperiment.processAnalysis and use that same
//   AudioRecord only for getTimestamp() anchors. No second capture stream is opened.
// - own: the experiment has no audio input, so we run our own AudioRecord on a dedicated reader
//   thread. UNPROCESSED is preferred because OEM audio enhancements (AEC/AGC) distort the
//   transient this feature exists to detect; it falls back to DEFAULT when the device refuses it.
//
//Timing: AudioRecord.getTimestamp(TIMEBASE_MONOTONIC) pairs a frame position with a
// SystemClock.elapsedRealtimeNanos value - the same clock that sensor event timestamps and
// ExperimentTimeReference use, so each anchor maps the audio stream onto experiment time.
// Anchors are taken inside the data-path (the tap call / the reader loop) right after frames were
// consumed, so the recorded frame count and the timestamp refer to the same instant. The slope
// between consecutive anchors also exposes the effective sample rate and clock drift.
public class SyncAudioTrack {

    private static final String TAG = "SyncAudioTrack";
    private static final int OWN_SAMPLE_RATE = 48000;
    private static final int OWN_CHUNK = 2048; //Frames per read() call in own mode
    private static final int ANCHOR_INTERVAL_FRAMES = 48000; //About one anchor per second at 48kHz

    private final ExperimentTimeReference timeReference;
    private final File sessionDir;

    private AudioRecord ownRecord = null; //Our own capture stream (own mode only)
    private AudioRecord sharedRecord = null; //The experiment's stream, used for anchors only (shared mode)
    private boolean ownModeFailed = false;
    private int ownAudioSource = MediaRecorder.AudioSource.DEFAULT; //What initOwn actually ended up with

    private Thread readerThread = null;

    private final List<Segment> segments = new ArrayList<>();
    private Segment current = null;

    private final Object writeLock = new Object(); //Serializes PCM writes and frame accounting

    public static class SyncAudioTrackException extends Exception {
        public SyncAudioTrackException(String message) {
            super(message);
        }
    }

    //One timing anchor: how many frames of this segment's file existed when the monotonic clock
    //showed nanoTime, and the experiment time of that instant. The experiment time is evaluated
    //at capture, while the segment's START is still the active mapping - after a PAUSE the
    //reference freezes, so asking for it later (at export) would clamp every anchor to the
    //pause instant.
    private static class Anchor {
        long frames;
        long nanoTime;
        double experimentTime;

        Anchor(long frames, long nanoTime, double experimentTime) {
            this.frames = frames;
            this.nanoTime = nanoTime;
            this.experimentTime = experimentTime;
        }
    }

    private static class Segment {
        int index;
        File pcmFile;
        FileOutputStream out;
        long frames = 0; //Frames written to pcmFile
        long startNano = 0; //elapsedRealtimeNanos at beginSegment
        double startExperimentTime = 0.0;
        double endExperimentTime = 0.0;
        String anchorSource = "getTimestamp"; //"startTime" if getTimestamp is unavailable
        List<Anchor> anchors = new ArrayList<>();
        long droppedFramesEstimate = 0;
        long lastCaptureFrame = -1; //framePosition of the previous anchor (drop detection)
        long lastAnchorFrames = -1; //frames written at the previous anchor

        Segment(int index, File pcmFile) {
            this.index = index;
            this.pcmFile = pcmFile;
        }
    }

    public SyncAudioTrack(ExperimentTimeReference timeReference, File baseDir) {
        this.timeReference = timeReference;
        this.sessionDir = new File(baseDir, "session");
        //A leftover directory means the previous session ended without an export (clear, process
        // death). It does not belong to this experiment instance, so it goes away here.
        deleteDir(sessionDir);
    }

    //Shared mode: tap the experiment's AudioRecord. The record reference is only used for
    // getTimestamp anchors; the samples arrive through tap().
    public void attachShared(AudioRecord shared) {
        sharedRecord = shared;
    }

    //Own mode: create our own AudioRecord. Prefers the unprocessed source so OEM enhancement
    // does not reshape the sync transient (see docs/sensors/13-audio.md); if the device cannot
    // initialize it we fall back to DEFAULT. Throws if neither works - callers surface the
    // message because an opt-in feature must not fail silently.
    public void initOwn() throws SyncAudioTrackException {
        if (ownRecord != null)
            return;
        int bufferSize = AudioRecord.getMinBufferSize(OWN_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0)
            throw new SyncAudioTrackException("No audio recording buffer size available (" + bufferSize + ").");
        //At least one second of headroom: a stalled reader thread loses samples only after the
        //internal buffer fills, so a bigger buffer is what turns scheduling jitter into a
        //non-event.
        bufferSize = Math.max(bufferSize, OWN_SAMPLE_RATE * 2);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ownRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, OWN_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (ownRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    ownAudioSource = MediaRecorder.AudioSource.UNPROCESSED;
                } else {
                    ownRecord.release();
                    ownRecord = null;
                }
            }
            if (ownRecord == null) {
                ownRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, OWN_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (ownRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    ownRecord.release();
                    ownRecord = null;
                    throw new SyncAudioTrackException("Could not initialize audio recording at " + OWN_SAMPLE_RATE + " Hz.");
                }
                ownAudioSource = MediaRecorder.AudioSource.DEFAULT;
            }
        } catch (SecurityException e) {
            //RECORD_AUDIO was revoked between enabling the toggle and initializing. The caller
            //surfaces this like any other init failure; the experiment itself keeps working.
            if (ownRecord != null) {
                ownRecord.release();
                ownRecord = null;
            }
            throw new SyncAudioTrackException("Audio recording permission is not granted.");
        }
    }

    //Test seam: lets a JVM test drive the recording pipeline without a real AudioRecord.
    boolean testUsable = false;

    public boolean isUsable() {
        return testUsable || sharedRecord != null || (ownRecord != null && !ownModeFailed);
    }

    //Called from PhyphoxExperiment.startAllIO after the START event is registered.
    public void beginSegment() {
        if (!isUsable() || current != null)
            return;
        sessionDir.mkdirs();
        Segment seg = new Segment(segments.size(), new File(sessionDir, "seg_" + segments.size() + ".pcm"));
        try {
            seg.out = new FileOutputStream(seg.pcmFile);
        } catch (IOException e) {
            Log.e(TAG, "Could not open sync audio file.", e);
            return;
        }
        seg.startNano = SystemClock.elapsedRealtimeNanos();
        seg.startExperimentTime = timeReference.getExperimentTime();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            seg.anchorSource = "startTime"; //No AudioRecord.getTimestamp: anchors fall back to the segment start
        current = seg;
        segments.add(seg);

        if (ownRecord != null) {
            try {
                ownRecord.startRecording();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Could not start sync audio recording.", e);
                endSegment();
                ownModeFailed = true;
                return;
            }
            startReaderThread();
        }
        takeAnchor(activeRecord());
    }

    //Called from PhyphoxExperiment.stopAllIO after the PAUSE event is registered.
    public void endSegment() {
        Segment seg = current;
        if (seg == null)
            return;
        current = null;
        if (ownRecord != null) {
            try {
                ownRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        Thread t = readerThread;
        readerThread = null;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        seg.endExperimentTime = timeReference.getExperimentTime();
        synchronized (writeLock) {
            try {
                seg.out.close();
            } catch (IOException ignored) {
            }
        }
        if (seg.frames == 0) {
            seg.pcmFile.delete();
            segments.remove(seg);
        }
    }

    //Stop capturing and release our hardware while keeping the recorded session for export.
    //Used when the user turns the feature off mid-run or the activity pauses it.
    public void pauseCapture() {
        endSegment();
        if (ownRecord != null) {
            ownRecord.release();
            ownRecord = null;
        }
        ownModeFailed = false;
    }

    //Drop everything recorded so far (clear-data and teardown path).
    public void discard() {
        endSegment();
        segments.clear();
        deleteDir(sessionDir);
    }

    public void release() {
        discard();
        if (ownRecord != null) {
            ownRecord.release();
            ownRecord = null;
        }
        sharedRecord = null;
    }

    private AudioRecord activeRecord() {
        return sharedRecord != null ? sharedRecord : ownRecord;
    }

    public int sampleRate() {
        AudioRecord rec = activeRecord();
        return rec != null ? rec.getSampleRate() : OWN_SAMPLE_RATE;
    }

    public boolean hasData() {
        for (Segment seg : segments)
            if (seg.frames > 0)
                return true;
        return false;
    }

    //Tap for the shared stream's float reads (PhyphoxExperiment).
    public void tap(float[] data, int count) {
        if (count <= 0)
            return;
        Segment seg = current;
        if (seg == null)
            return;
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            float v = data[i];
            if (v > 1.0f)
                v = 1.0f;
            else if (v < -1.0f)
                v = -1.0f;
            short s = (short) (v * Short.MAX_VALUE);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        writePcm(seg, pcm);
        maybeAnchor(seg);
    }

    //Tap for the shared stream's 16-bit reads (compatibility format and pre-23 devices).
    public void tap(short[] data, int count) {
        if (count <= 0)
            return;
        Segment seg = current;
        if (seg == null)
            return;
        byte[] pcm = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            pcm[2 * i] = (byte) data[i];
            pcm[2 * i + 1] = (byte) (data[i] >> 8);
        }
        writePcm(seg, pcm);
        maybeAnchor(seg);
    }

    private void writePcm(Segment seg, byte[] pcm) {
        synchronized (writeLock) {
            try {
                seg.out.write(pcm);
                seg.frames += pcm.length / 2;
            } catch (IOException e) {
                Log.e(TAG, "Failed writing sync audio.", e);
            }
        }
    }

    private void maybeAnchor(Segment seg) {
        if (seg.frames - seg.lastAnchorFrames >= ANCHOR_INTERVAL_FRAMES || seg.lastAnchorFrames < 0)
            takeAnchor(seg, activeRecord());
    }

    //Pair the number of frames consumed so far with a monotonic-clock timestamp. The caller is
    //always the thread that consumes the stream, so "frames" and nanoTime describe the same
    //instant. framePosition additionally tracks the HAL-side frame counter: if it advances more
    //than the frames we received, the capture buffer overran and audio was lost silently.
    private void takeAnchor(AudioRecord rec) {
        Segment seg = current;
        if (seg != null)
            takeAnchor(seg, rec);
    }

    //getTimestamp's nanoTime is supposed to be CLOCK_MONOTONIC (= elapsedRealtimeNanos), but
    //some devices answer in a different domain - on vivo it is off by the phone's total
    //suspend time (~14 h), which would clamp every anchor to the segment start. Detect the
    //domain mismatch once per recording and fall back to the reader's own clock; the reader's
    //scheduling jitter (~10 ms) replaces a completely wrong clock.
    private static final long TIMESTAMP_DOMAIN_LIMIT_NS = 500_000_000L; //500 ms
    private boolean timestampDomainBroken = false;

    private void takeAnchor(Segment seg, AudioRecord rec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || rec == null)
            return;
        AudioTimestamp ts = new AudioTimestamp();
        if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS)
            return;
        long now = android.os.SystemClock.elapsedRealtimeNanos();
        if (!timestampDomainBroken && Math.abs(now - ts.nanoTime) > TIMESTAMP_DOMAIN_LIMIT_NS) {
            timestampDomainBroken = true;
            Log.w("SyncAudioTrack", "AudioTimestamp clock domain mismatch (off by "
                    + (now - ts.nanoTime) / 1_000_000_000.0 + " s) - anchoring on the reader clock instead.");
        }
        long nano = timestampDomainBroken ? now : ts.nanoTime;
        double experimentTime = timeReference.getExperimentTimeFromEvent(nano);
        synchronized (writeLock) {
            if (seg.lastCaptureFrame >= 0) {
                long captured = ts.framePosition - seg.lastCaptureFrame;
                long received = seg.frames - seg.lastAnchorFrames;
                if (captured > received)
                    seg.droppedFramesEstimate += captured - received;
            }
            seg.lastCaptureFrame = ts.framePosition;
            seg.lastAnchorFrames = seg.frames;
            if (timestampDomainBroken)
                seg.anchorSource = "elapsedRealtime"; //Raw HAL timestamps unusable on this device
            seg.anchors.add(new Anchor(seg.frames, nano, experimentTime));
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            //The audio-capture priority Android intends for recorders; keeps the reader ahead of
            //UI/analysis work so the HAL buffer never has time to overrun.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] buffer = new short[OWN_CHUNK];
            while (readerThread != null && Thread.currentThread() == readerThread) {
                Segment seg = current;
                if (seg == null)
                    break;
                int n;
                try {
                    n = ownRecord.read(buffer, 0, buffer.length);
                } catch (Exception e) {
                    Log.e(TAG, "Sync audio read failed.", e);
                    break;
                }
                if (n > 0)
                    tap(buffer, n);
            }
        }, "SyncAudioTrack");
        readerThread.start();
    }

    //Write every recorded segment plus its timing sidecar into an export zip under sync/.
    public void writeToZip(ZipOutputStream zstream, String appVersion) throws IOException {
        if (!hasData())
            return;

        for (Segment seg : segments) {
            //An export can happen mid-measurement with the current segment still open: flush it
            //and size the WAV header from the bytes actually on disk, so a file that is still
            //growing under the read cannot end up longer than its header promises.
            long frames;
            synchronized (writeLock) {
                try {
                    seg.out.flush();
                } catch (IOException ignored) {
                }
                frames = seg.pcmFile.length() / 2;
            }
            zstream.putNextEntry(new ZipEntry("sync/audio_" + seg.index + ".wav"));
            writeWavHeader(zstream, frames, sampleRate());
            FileInputStream in = new FileInputStream(seg.pcmFile);
            try {
                byte[] chunk = new byte[8192];
                long remaining = frames * 2;
                int n;
                while (remaining > 0 && (n = in.read(chunk, 0, (int) Math.min(chunk.length, remaining))) != -1) {
                    zstream.write(chunk, 0, n);
                    remaining -= n;
                }
            } finally {
                in.close();
            }
            zstream.closeEntry();
        }

        zstream.putNextEntry(new ZipEntry("sync/sync.json"));
        zstream.write(toJson(appVersion).toString().getBytes("UTF-8"));
        zstream.closeEntry();
    }

    private void writeWavHeader(OutputStream out, long frames, int rate) throws IOException {
        long dataLen = frames * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        putLE32(header, 4, 36 + dataLen);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        putLE32(header, 16, 16); //fmt chunk size
        putLE16(header, 20, 1); //PCM
        putLE16(header, 22, 1); //mono
        putLE32(header, 24, rate);
        putLE32(header, 28, rate * 2); //byte rate
        putLE16(header, 32, 2); //block align
        putLE16(header, 34, 16); //bits per sample
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        putLE32(header, 40, dataLen);
        out.write(header);
    }

    private static void putLE32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putLE16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private JSONObject toJson(String appVersion) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "Phyerma " + appVersion);
            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            root.put("device", device);
            root.put("sampleRate", sampleRate());
            root.put("encoding", "pcm_s16le");
            root.put("channels", 1);
            root.put("timeBase", "elapsedRealtimeNanos");
            root.put("sharedStream", sharedRecord != null);
            if (sharedRecord == null)
                root.put("audioSource", ownAudioSource == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED" : "DEFAULT");

            JSONArray segs = new JSONArray();
            for (Segment seg : segments) {
                JSONObject s = new JSONObject();
                s.put("index", seg.index);
                s.put("file", "audio_" + seg.index + ".wav");
                s.put("frames", seg.frames);
                s.put("anchorSource", seg.anchorSource);
                s.put("experimentTimeRange", new JSONArray(new double[]{seg.startExperimentTime, seg.endExperimentTime}));
                s.put("droppedFramesEstimate", seg.droppedFramesEstimate);
                JSONArray anchors = new JSONArray();
                for (Anchor a : seg.anchors) {
                    JSONArray entry = new JSONArray();
                    entry.put(a.frames); //Frames written at anchor instant (file index)
                    entry.put(a.nanoTime); //elapsedRealtimeNanos
                    entry.put(a.experimentTime);
                    anchors.put(entry);
                }
                s.put("anchors", anchors);
                segs.put(s);
            }
            root.put("segments", segs);

            JSONArray events = new JSONArray();
            for (ExperimentTimeReference.TimeMapping m : timeReference.getTimeMappings()) {
                JSONObject e = new JSONObject();
                e.put("event", m.event.name());
                e.put("experimentTime", m.experimentTime);
                e.put("systemTime", m.systemTime);
                events.put(e);
            }
            root.put("events", events);
        } catch (JSONException e) {
            Log.e(TAG, "Could not build sync metadata.", e);
        }
        return root;
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                f.delete();
        dir.delete();
    }
}

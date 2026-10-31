package de.rwth_aachen.phyphox.camera;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.rwth_aachen.phyphox.DataBuffer;
import de.rwth_aachen.phyphox.ExperimentTimeReference;
import de.rwth_aachen.phyphox.camera.analyzer.AnalyzingOpenGLRenderer;
import de.rwth_aachen.phyphox.camera.analyzer.VideoEncoder;

//VideoInput is the experiment-side anchor of the <video> input declared in a .phyphox file. It
//holds the requested settings (resolution, frame rate, audio track) and owns the exported files;
//the actual encoding happens in VideoEncoder on the GL thread of AnalyzingOpenGLRenderer, which is
//shared with the camera pipeline - a <camera> and a <video> input in the same experiment ride the
//same camera session.
//
//Every encoded frame's camera timestamp is written to the `t` output buffer in experiment time,
//and the MP4 itself carries the same timestamps as presentation times. Cross-device alignment can
//then use either the wav-style content (a clap in the optional audio track, a flash in the image)
//or simply the frame times.
public class VideoInput implements Serializable {

    private static final String TAG = "VideoInput";

    public final int resolutionHeight; //Requested long side (480/720/1080), device may adjust
    public final double fps;
    public final boolean withAudio;
    public final DataBuffer dataT; //Experiment time of each encoded frame
    private final ExperimentTimeReference timeReference;

    private transient File sessionDir;
    private transient List<Segment> segments = new ArrayList<>();
    private transient Segment current = null;
    public transient String error = null;

    //What the camera-clock calibration added to this run's frame times; filled in by CameraInput
    //whenever the camera (re)starts and written to video.json, because it changes the numbers a
    //reader of the export sees.
    private transient String clockLens = null;
    private transient long clockBiasNs = 0;
    private transient boolean clockCalibrated = false;
    private transient long clockCalibratedAtMs = 0;
    private transient int clockSamples = 0;

    public synchronized void setCameraClockCalibration(String lens, long biasNs, boolean calibrated, long calibratedAtMs, int samples) {
        clockLens = lens;
        clockBiasNs = biasNs;
        clockCalibrated = calibrated;
        clockCalibratedAtMs = calibratedAtMs;
        clockSamples = samples;
    }

    private static class Segment {
        int index;
        File file;
        long startNanos;
        double startExperimentTime;
        double endExperimentTime;
        long frames;
        long framesSubmitted;
        long packetsSkipped;
        long framesInContainer;
        boolean missing;
        final java.util.List<Double> frameTimes = new java.util.ArrayList<>();

        Segment(int index, File file, long startNanos, double startExperimentTime) {
            this.index = index;
            this.file = file;
            this.startNanos = startNanos;
            this.startExperimentTime = startExperimentTime;
        }
    }

    public VideoInput(int resolutionHeight, double fps, boolean withAudio, DataBuffer dataT, ExperimentTimeReference timeReference, File baseDir) {
        this.resolutionHeight = resolutionHeight;
        this.fps = fps;
        this.withAudio = withAudio;
        this.dataT = dataT;
        this.timeReference = timeReference;
        initSessionDir(baseDir);
    }

    private void initSessionDir(File baseDir) {
        if (baseDir == null)
            return;
        sessionDir = new File(baseDir, "video");
        //A leftover directory means the previous session ended without an export (clear, process
        //death). It does not belong to this experiment instance, so it goes away here.
        File[] files = sessionDir.listFiles();
        if (files != null)
            for (File f : files)
                f.delete();
    }

    //Called on the GL executor thread (from AnalyzingOpenGLRenderer). Opens the next segment file.
    public synchronized File beginSegmentFile() {
        if (sessionDir == null)
            return null;
        sessionDir.mkdirs();
        File file = new File(sessionDir, "seg_" + segments.size() + ".mp4");
        Segment seg = new Segment(segments.size(), file, SystemClock.elapsedRealtimeNanos(), timeReference.getExperimentTime());
        segments.add(seg);
        current = seg;
        return file;
    }

    //Experiment time of an arbitrary monotonic timestamp, found by interval rather than the last
    //mapping: getExperimentTimeFromEvent only consults the latest mapping, so frames flushed or
    //read back after a PAUSE would all clamp to the pause instant. This walks the event list and
    //uses the START whose interval actually contains the timestamp.
    public double experimentTimeAt(long eventTimeNanos) {
        double candidate = -1;
        java.util.List<ExperimentTimeReference.TimeMapping> maps = timeReference.getTimeMappings();
        for (ExperimentTimeReference.TimeMapping m : maps) {
            if (m.event == ExperimentTimeReference.TimeMappingEvent.START && eventTimeNanos >= m.eventTime)
                candidate = m.experimentTime + (eventTimeNanos - m.eventTime) * 1e-9;
            else if (m.event == ExperimentTimeReference.TimeMappingEvent.PAUSE && eventTimeNanos < m.eventTime)
                break;
        }
        if (candidate >= 0)
            return candidate;
        //The timestamp sits outside every START interval - e.g. a camera clock whose domain is
        //behind elapsedRealtime by some uncorrected offset (see CalibrationStore). Extrapolate from the first mapping
        //instead of clamping to the last event the way getExperimentTimeFromEvent would.
        if (!maps.isEmpty()) {
            ExperimentTimeReference.TimeMapping f = maps.get(0);
            return f.experimentTime + (eventTimeNanos - f.eventTime) * 1e-9;
        }
        return 0;
    }

    //Called on the GL executor thread after the encoder has flushed. framesSubmitted counts the
    //camera frames handed to the codec, framesWritten the packets that made it into the file and
    //packetsSkipped the ones the muxer rejected - all three belong in the metadata because on
    //real hardware the encoder may reorder or drop frames. writtenNanos carries the monotonic
    //timestamp of every written frame - their experiment times are the authoritative
    //frame->time mapping of the MP4.
    public synchronized void endSegmentFile(long framesWritten, long framesSubmitted, long packetsSkipped, java.util.List<Long> writtenNanos) {
        Segment seg = current;
        if (seg == null)
            return;
        current = null;
        seg.endExperimentTime = timeReference.getExperimentTime();
        seg.frames = framesWritten;
        seg.framesSubmitted = framesSubmitted;
        seg.packetsSkipped = packetsSkipped;
        for (long nano : writtenNanos)
            seg.frameTimes.add(experimentTimeAt(nano));
        //The muxer's sample count is the last truth: on devices whose MediaMuxer loses accepted
        //packets, the written count alone would overstate what is inside the file.
        countContainerFrames(seg);
        if (framesWritten == 0) {
            seg.file.delete();
            segments.remove(seg);
        }
    }

    private void countContainerFrames(Segment seg) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(seg.file.getAbsolutePath());
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0)
                return;
            extractor.selectTrack(videoTrack);
            long count = 0;
            while (extractor.getSampleTime() >= 0) {
                count++;
                extractor.advance();
            }
            seg.framesInContainer = count;
        } catch (Exception e) {
            android.util.Log.w("VideoInput", "Could not count container frames: " + e.getMessage());
        } finally {
            extractor.release();
        }
    }

    //Only segments the encoder has finished count: the one still being recorded is an MP4 without
    //its index (the muxer writes that on stop) and cannot be played, and its frame bookkeeping is
    //still zero. Called from the export thread while the GL thread keeps recording, hence the lock.
    private synchronized List<Segment> finishedSegments() {
        List<Segment> finished = new ArrayList<>();
        if (segments != null)
            for (Segment seg : segments)
                if (seg != current && seg.frames > 0)
                    finished.add(seg);
        return finished;
    }

    public boolean hasData() {
        return !finishedSegments().isEmpty();
    }

    //True while a segment is being recorded, i.e. an export now would leave that segment out.
    public synchronized boolean isRecording() {
        return current != null;
    }

    //Clear-data semantics: a clear wipes the run's recordings together with the buffers.
    public synchronized void discard() {
        if (segments != null)
            segments.clear();
        current = null;
        if (sessionDir != null) {
            File[] files = sessionDir.listFiles();
            if (files != null)
                for (File f : files)
                    f.delete();
        }
    }

    public void writeToZip(ZipOutputStream zstream, String appVersion) throws IOException {
        List<Segment> finished = finishedSegments();
        if (finished.isEmpty())
            return;
        boolean inProgress = isRecording();
        for (Segment seg : finished) {
            //A segment file may be gone (files deleted out from under the app) - skip the zip
            //entry but keep the segment in video.json marked missing so no metadata is lost.
            if (seg.file.exists()) {
                String name = "video/video_" + seg.index + ".mp4";
                zstream.putNextEntry(new ZipEntry(name));
                FileInputStream in = new FileInputStream(seg.file);
                try {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1)
                        zstream.write(chunk, 0, n);
                } finally {
                    in.close();
                }
                zstream.closeEntry();
            } else {
                seg.missing = true;
            }
        }
        zstream.putNextEntry(new ZipEntry("video/video.json"));
        zstream.write(toJson(appVersion, finished, inProgress).toString().getBytes("UTF-8"));
        zstream.closeEntry();
    }

    private JSONObject toJson(String appVersion, List<Segment> finished, boolean inProgress) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "Phyerma " + appVersion);
            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            root.put("device", device);
            root.put("resolutionHeight", resolutionHeight);
            root.put("fpsTarget", fps);
            root.put("audio", withAudio);
            root.put("timeBase", "elapsedRealtimeNanos");
            JSONObject clock = new JSONObject();
            clock.put("calibrated", clockCalibrated);
            clock.put("lens", clockLens == null ? JSONObject.NULL : clockLens);
            clock.put("biasNs", clockBiasNs);
            clock.put("calibratedAtMs", clockCalibratedAtMs);
            clock.put("samples", clockSamples);
            clock.put("note", "biasNs is already added to every frame timestamp: frameTimes and the MP4 presentation times include it");
            root.put("cameraClock", clock);

            JSONArray segs = new JSONArray();
            for (Segment seg : finished) {
                JSONObject s = new JSONObject();
                s.put("index", seg.index);
                s.put("file", "video_" + seg.index + ".mp4");
                s.put("frames", seg.frames);
                s.put("framesSubmitted", seg.framesSubmitted);
                s.put("packetsSkipped", seg.packetsSkipped);
                s.put("framesInContainer", seg.framesInContainer);
                if (seg.missing)
                    s.put("fileMissing", true);
                //Exact per-frame experiment times read back from the finished container - index
                //into video_<i>.mp4's video track. This is the authoritative mapping; the `t`
                //buffer lists frames accepted by the muxer which may be a superset.
                org.json.JSONArray times = new org.json.JSONArray();
                for (double ft : seg.frameTimes)
                    times.put(ft);
                s.put("frameTimes", times);
                s.put("startNanos", seg.startNanos);
                s.put("experimentTimeRange", new JSONArray(new double[]{seg.startExperimentTime, seg.endExperimentTime}));
                segs.put(s);
            }
            root.put("segments", segs);
            if (inProgress)
                root.put("inProgressSegmentExcluded", true);

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
            Log.e(TAG, "Could not build video metadata.", e);
        }
        return root;
    }

}

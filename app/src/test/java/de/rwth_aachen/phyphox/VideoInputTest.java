package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.rwth_aachen.phyphox.camera.VideoInput;

//Pins the video-input bookkeeping that the export path relies on: pause/resume must produce one
//MP4 per segment, empty segments must not ship, and video.json must carry the per-segment
//experiment-time ranges that offline alignment tooling reads.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35) //Robolectric's max supported SDK (36) is below the app's targetSdk (37)
public class VideoInputTest {

    private File baseDir;
    private ExperimentTimeReference timeReference;
    private VideoInput video;

    @Before
    public void setUp() {
        baseDir = new File(System.getProperty("java.io.tmpdir"), "videotest-" + System.nanoTime());
        baseDir.mkdirs();
        timeReference = new ExperimentTimeReference(null);
        video = new VideoInput(720, 30.0, true, null, timeReference, baseDir);
    }

    private File writeSegment(long frames) throws Exception {
        File f = video.beginSegmentFile();
        assertNotNull(f);
        FileOutputStream out = new FileOutputStream(f);
        out.write(new byte[]{1, 2, 3, 4});
        out.close();
        video.endSegmentFile(frames, frames, 0, java.util.Collections.emptyList());
        return f;
    }

    @Test
    public void pauseResumeProducesTwoSegments() throws Exception {
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        writeSegment(30);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        writeSegment(60);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);

        assertTrue(video.hasData());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        video.writeToZip(zip, "1.0-test");
        zip.close();

        Map<String, byte[]> entries = new HashMap<>();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        ZipEntry e;
        while ((e = in.getNextEntry()) != null)
            entries.put(e.getName(), in.readAllBytes());
        in.close();

        assertTrue(entries.containsKey("video/video_0.mp4"));
        assertTrue(entries.containsKey("video/video_1.mp4"));
        assertTrue(entries.containsKey("video/video.json"));
        assertEquals(4, entries.get("video/video_0.mp4").length);

        JSONObject json = new JSONObject(new String(entries.get("video/video.json"), "UTF-8"));
        JSONArray segments = json.getJSONArray("segments");
        assertEquals(2, segments.length());
        assertEquals(30, segments.getJSONObject(0).getLong("frames"));
        assertEquals(30, segments.getJSONObject(0).getLong("framesSubmitted"));
        assertEquals(60, segments.getJSONObject(1).getLong("frames"));
        assertTrue(json.getBoolean("audio"));
        assertEquals("elapsedRealtimeNanos", json.getString("timeBase"));
        JSONArray events = json.getJSONArray("events");
        assertTrue(events.length() >= 3);
    }

    @Test
    public void zeroFrameSegmentIsDropped() throws Exception {
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        File f = writeSegment(0);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);

        assertFalse(f.exists());
        assertFalse(video.hasData());
    }

    @Test
    public void discardClearsAllSegments() throws Exception {
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        writeSegment(10);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);

        video.discard();
        assertFalse(video.hasData());
    }

    @Test
    public void aSegmentStillRecordingIsNotExported() throws Exception {
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        writeSegment(30);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        File open = video.beginSegmentFile();
        FileOutputStream out = new FileOutputStream(open);
        out.write(new byte[]{9, 9});
        out.close();

        assertTrue(video.isRecording());
        assertTrue("the finished segment still counts", video.hasData());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        video.writeToZip(zip, "1.0-test");
        zip.close();

        Map<String, byte[]> entries = new HashMap<>();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        ZipEntry e;
        while ((e = in.getNextEntry()) != null)
            entries.put(e.getName(), in.readAllBytes());
        in.close();

        assertTrue(entries.containsKey("video/video_0.mp4"));
        assertFalse("an MP4 that has no index yet cannot be played", entries.containsKey("video/video_1.mp4"));
        JSONObject json = new JSONObject(new String(entries.get("video/video.json"), "UTF-8"));
        assertEquals(1, json.getJSONArray("segments").length());
        assertTrue(json.getBoolean("inProgressSegmentExcluded"));
    }

    @Test
    public void aLoneSegmentStillRecordingIsNotData() throws Exception {
        timeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        video.beginSegmentFile();

        assertTrue(video.isRecording());
        assertFalse(video.hasData());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        video.writeToZip(zip, "1.0-test");
        zip.close();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertNull("nothing is written when no segment is finished", in.getNextEntry());
        in.close();
    }
}

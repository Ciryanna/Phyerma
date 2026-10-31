package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.rwth_aachen.phyphox.camera.VideoInput;
import de.rwth_aachen.phyphox.device.CalibrationStore;
import de.rwth_aachen.phyphox.device.ProfileMeta;

//The calibration tab decides what a recording says about its phone: ticked items go into the
//export's meta/calibration.csv, unticked ones leave no trace, and a camera offset that was
//applied to video frame times is always declared in video.json.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CalibrationMetaTest {

    private static final String EXPERIMENT =
            "<phyphox version=\"1.20\">"
                    + "<title>Calibration meta</title>"
                    + "<category>Test</category>"
                    + "<description>One export set.</description>"
                    + "<data-containers><container size=\"8\" init=\"1,2,3\">x</container></data-containers>"
                    + "<views><view label=\"View\"><value label=\"v\"><input>x</input></value></view></views>"
                    + "<export><set name=\"Data\"><data name=\"x\">x</data></set></export>"
                    + "</phyphox>";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("phyphox", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void nothingIsWrittenUntilTheUserTicksIt() {
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 200_000_000L, 7);
        CalibrationStore.setIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK, false);
        assertTrue(ProfileMeta.collect(context).isEmpty());
    }

    @Test
    public void savingACalibrationTicksItsExportSwitchOnlyTheFirstTime() {
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 1_000_000L, 5);
        assertTrue(CalibrationStore.isIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK));
        CalibrationStore.setIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK, false);
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 2_000_000L, 6);
        assertFalse("a re-measurement must not undo the choice",
                CalibrationStore.isIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK));
    }

    @Test
    public void lensesAreStoredSeparatelyAndClearOnlyTouchesOne() {
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 200_000_000L, 7);
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_FRONT, -30_000_000L, 5);
        assertEquals(200_000_000L, CalibrationStore.cameraBiasNs(context, CalibrationStore.LENS_BACK));
        assertEquals(-30_000_000L, CalibrationStore.cameraBiasNs(context, CalibrationStore.LENS_FRONT));
        CalibrationStore.clearCameraBias(context, CalibrationStore.LENS_FRONT);
        assertFalse(CalibrationStore.hasCalibration(context, CalibrationStore.LENS_FRONT));
        assertEquals(0L, CalibrationStore.cameraBiasNs(context, CalibrationStore.LENS_FRONT));
        assertTrue(CalibrationStore.hasCalibration(context, CalibrationStore.LENS_BACK));
        assertEquals("CameraSelector.LENS_FACING_FRONT is 0", CalibrationStore.LENS_FRONT, CalibrationStore.lensKey(0));
        assertEquals("CameraSelector.LENS_FACING_BACK is 1", CalibrationStore.LENS_BACK, CalibrationStore.lensKey(1));
    }

    @Test
    public void aCalibrationFromAnEarlierBuildStillCountsAsTheBackLens() {
        context.getSharedPreferences("phyphox", Context.MODE_PRIVATE).edit()
                .putLong("calib_camera_bias_ns", 170_000_000L).commit();
        assertTrue(CalibrationStore.hasCalibration(context, CalibrationStore.LENS_BACK));
        assertEquals(170_000_000L, CalibrationStore.cameraBiasNs(context, CalibrationStore.LENS_BACK));
        assertFalse(CalibrationStore.hasCalibration(context, CalibrationStore.LENS_FRONT));
    }

    @Test
    public void tickedItemsSayWhatWasMeasuredAndWhatIsOnlyReference() {
        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 200_000_000L, 7);
        CalibrationStore.setIncluded(context, CalibrationStore.ITEM_DEVICE, true);
        Map<String, String> rows = ProfileMeta.collect(context);
        assertEquals("200.000", rows.get("camera_clock.back.bias_ms"));
        assertEquals("7", rows.get("camera_clock.back.samples"));
        assertNotNull(rows.get("camera_clock.back.measured_at"));
        assertFalse(rows.containsKey("camera_clock.front.bias_ms"));
        assertTrue(rows.containsKey("device.os_skin"));
        assertTrue(rows.containsKey("device.android_api"));
        assertTrue(rows.containsKey("device.db_match"));
    }

    @Test
    public void aTickedButUncalibratedClockSaysSoInsteadOfStayingSilent() {
        CalibrationStore.setIncluded(context, CalibrationStore.ITEM_CAMERA_CLOCK, true);
        Map<String, String> rows = ProfileMeta.collect(context);
        assertTrue(rows.get("camera_clock.status").startsWith("not calibrated"));
    }

    //The fake cameras can be registered once per test, so a test builds its activity once.
    private File export(Experiment activity) {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(EXPERIMENT.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue(experiment.message, experiment.loaded);
        return experiment.exporter.exportDirect(activity.getCacheDir(), true, "calmeta", activity);
    }

    private String entry(File zip, String name) throws Exception {
        try (ZipFile archive = new ZipFile(zip)) {
            ZipEntry e = archive.getEntry(name);
            return e == null ? null : new String(archive.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theExportCarriesCalibrationCsvOnlyWhenSomethingIsTicked() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        assertNull(entry(export(activity), "meta/calibration.csv"));

        CalibrationStore.saveCameraBias(context, CalibrationStore.LENS_BACK, 200_000_000L, 7);
        String csv = entry(export(activity), "meta/calibration.csv");
        assertNotNull(csv);
        assertTrue(csv, csv.startsWith("\"property\",\"value\""));
        assertTrue(csv, csv.contains("\"camera_clock.back.bias_ms\",\"200.000\""));
    }

    @Test
    public void videoJsonAlwaysDeclaresTheOffsetItAdded() throws Exception {
        File baseDir = new File(System.getProperty("java.io.tmpdir"), "calvideo-" + System.nanoTime());
        baseDir.mkdirs();
        ExperimentTimeReference ref = new ExperimentTimeReference(null);
        VideoInput video = new VideoInput(720, 30.0, false, null, ref, baseDir);
        video.setCameraClockCalibration(CalibrationStore.LENS_BACK, 200_000_000L, true, 123L, 7);
        ref.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        File seg = video.beginSegmentFile();
        java.nio.file.Files.write(seg.toPath(), new byte[]{1, 2, 3, 4});
        video.endSegmentFile(10, 10, 0, java.util.Collections.emptyList());
        ref.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            video.writeToZip(zip, "test");
        }
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null)
                entries.put(e.getName(), in.readAllBytes());
        }
        JSONObject clock = new JSONObject(new String(entries.get("video/video.json"), "UTF-8")).getJSONObject("cameraClock");
        assertTrue(clock.getBoolean("calibrated"));
        assertEquals("back", clock.getString("lens"));
        assertEquals(200_000_000L, clock.getLong("biasNs"));
        assertEquals(7, clock.getInt("samples"));
    }
}

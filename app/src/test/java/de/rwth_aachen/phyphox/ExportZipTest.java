package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.rwth_aachen.phyphox.camera.VideoInput;

//There is exactly one export format: a zip of plain CSV files with meta/ next to them, and the
//recorded video next to that when the experiment has any. These tests pin that contract, and that
//a failed export is reported instead of handing over a truncated file.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExportZipTest {

    private static final String EXPERIMENT =
            "<phyphox version=\"1.20\">"
                    + "<title>Export zip</title>"
                    + "<category>Test</category>"
                    + "<description>One export set.</description>"
                    + "<data-containers><container size=\"8\" init=\"1,2,3\">x</container></data-containers>"
                    + "<views><view label=\"View\"><value label=\"v\"><input>x</input></value></view></views>"
                    + "<export><set name=\"Data\"><data name=\"x\">x</data></set></export>"
                    + "</phyphox>";

    private PhyphoxExperiment load(Experiment activity) {
        PhyphoxExperiment experiment = CorpusTestEnvironment.load(
                new ByteArrayInputStream(EXPERIMENT.getBytes(StandardCharsets.UTF_8)), activity);
        assertTrue("Test experiment failed to load: " + experiment.message, experiment.loaded);
        return experiment;
    }

    private Set<String> entries(File zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements())
                names.add(all.nextElement().getName());
        }
        return names;
    }

    @Test
    public void theExportIsAlwaysAZipWithDataAndMeta() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = load(activity);

        File file = experiment.exporter.exportDirect(activity.getCacheDir(), true, "onezip", activity);

        assertNotNull(file);
        assertEquals("onezip.zip", file.getName());
        assertEquals("application/zip", experiment.exporter.getType());
        Set<String> names = entries(file);
        assertTrue(names.toString(), names.contains("Data.csv"));
        assertTrue(names.toString(), names.contains("meta/device.csv"));
        assertTrue(names.toString(), names.contains("meta/time.csv"));
        assertFalse("no video was recorded", names.contains("video/video.json"));
    }

    @Test
    public void recordedVideoRidesAlongWithTheData() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = load(activity);

        File baseDir = new File(activity.getCacheDir(), "videoexport-" + System.nanoTime());
        baseDir.mkdirs();
        VideoInput video = new VideoInput(720, 30.0, false, null, experiment.experimentTimeReference, baseDir);
        experiment.videoInput = video;
        experiment.experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        File segment = video.beginSegmentFile();
        try (FileOutputStream out = new FileOutputStream(segment)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        video.endSegmentFile(10, 10, 0, java.util.Collections.emptyList());
        experiment.experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);

        assertTrue(experiment.exporter.hasContent());
        File withMedia = experiment.exporter.exportDirect(activity.getCacheDir(), true, "media", activity);
        Set<String> names = entries(withMedia);
        assertTrue(names.toString(), names.contains("video/video_0.mp4"));
        assertTrue(names.toString(), names.contains("video/video.json"));

        File dataOnly = experiment.exporter.exportDirect(activity.getCacheDir(), false, "dataonly", activity);
        assertFalse("the single-graph export carries no video",
                entries(dataOnly).contains("video/video_0.mp4"));
    }

    @Test
    public void aFailedExportReturnsNothingInsteadOfATruncatedZip() throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        PhyphoxExperiment experiment = load(activity);

        File missing = new File(activity.getCacheDir(), "no-such-dir-" + System.nanoTime());

        assertNull(experiment.exporter.exportDirect(missing, true, "broken", activity));
    }
}

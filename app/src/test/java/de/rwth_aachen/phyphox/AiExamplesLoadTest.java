package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.rwth_aachen.phyphox.ai.AiEdits;
import de.rwth_aachen.phyphox.ai.AiReply;

//The experiments that are put in front of the model as examples, and the edit example in the task
//text, have to be what they claim to be: files this app loads. A wrong example would teach the
//model the wrong thing in every single request.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AiExamplesLoadTest {
    private static final File ASSETS = new File("src/main/assets/ai");

    private static String text(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void everyExampleLoads() throws Exception {
        File[] files = new File(ASSETS, "examples").listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("no examples found", files != null && files.length >= 5);
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        List<String> failures = new ArrayList<>();
        for (File f : files) {
            PhyphoxExperiment e = CorpusTestEnvironment.load(f, activity);
            if (!e.loaded)
                failures.add(f.getName() + ": " + e.message);
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void editExampleInTheTaskTextWorksOnTheExampleItNames() throws Exception {
        String task = text(new File(ASSETS, "edit_task.txt"));
        AiReply reply = AiReply.parse(task.substring(task.indexOf("The answer is:")));
        assertEquals(2, reply.edits.size());
        String light = text(new File(ASSETS, "examples/light_alarm.xml"));
        String changed = AiEdits.apply(light, reply.edits);
        assertTrue(changed.contains("init=\"800\""));
        assertTrue(changed.contains("default=\"800\""));
        assertFalse(changed.contains("500"));
        PhyphoxExperiment e = CorpusTestEnvironment.load(new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8)),
                CorpusTestEnvironment.fullyEquippedActivity());
        assertTrue(e.message, e.loaded);
    }

    @Test
    public void examplesAreFreeOfAppPlaceholders() throws Exception {
        Pattern placeholder = Pattern.compile("\\[\\[[a-z_]+\\]\\]");
        for (File f : new File(ASSETS, "examples").listFiles()) {
            Matcher m = placeholder.matcher(text(f));
            assertFalse(f.getName(), m.find());
        }
    }
}

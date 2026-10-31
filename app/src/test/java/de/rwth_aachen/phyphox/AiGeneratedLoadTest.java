package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

//Manual harness for the AI tab's prompt: loads every .phyphox file in the directory named by the
//environment variable PHYERMA_AI_GEN_DIR (files the model generated) through the real parser
//and fails with each parser message. Skipped when the variable is not set, so CI never needs
//a network or an API key.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AiGeneratedLoadTest {

    @Test
    public void everyGeneratedExperimentLoads() throws Exception {
        String dir = System.getenv("PHYERMA_AI_GEN_DIR");
        assumeTrue("PHYERMA_AI_GEN_DIR not set", dir != null && !dir.isEmpty());
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".phyphox"));
        assumeTrue("no generated files", files != null && files.length > 0);

        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity();
        List<String> failures = new ArrayList<>();
        for (File f : files) {
            PhyphoxExperiment e = CorpusTestEnvironment.load(f, activity);
            System.out.println("AIGEN " + f.getName() + " -> " + (e.loaded ? "OK" : "FAIL: " + e.message));
            if (!e.loaded)
                failures.add(f.getName() + ": " + e.message);
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }
}

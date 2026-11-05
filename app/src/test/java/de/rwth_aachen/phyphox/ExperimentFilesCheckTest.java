package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import de.rwth_aachen.phyphox.ai.ContainerRepair;

//The check for experiment files, the same one the app applies when it opens a file.
//
//  * bundledExperimentsLoad: every experiment shipped in the app (assets/experiments and
//    assets/ai/examples) must load. Always runs.
//  * givenFilesLoad: the files and folders named by the environment variable PHYERMA_CHECK (several
//    separated by the platform's path separator) are checked the same way. Skipped when it is not
//    set. tools/check_experiments.sh sets it from its arguments.
//
//Both go through the real parser (a device with every sensor, so that only actual mistakes fail).
//The result of each file is printed and also written to app/build/experiment-check-bundled.txt / -given.txt.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExperimentFilesCheckTest {

    @Test
    public void bundledExperimentsLoad() throws Exception {
        List<File> files = new ArrayList<>();
        collect(new File("src/main/assets/experiments"), files);
        collect(new File("src/main/assets/ai/examples"), files);
        assumeTrue("no bundled experiments found from " + new File(".").getAbsolutePath(), !files.isEmpty());
        List<String> failures = check(files, "bundled");
        assertTrue(failures.size() + " of " + files.size() + " bundled experiments do not load:\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void givenFilesLoad() throws Exception {
        String given = System.getenv("PHYERMA_CHECK");
        assumeTrue("PHYERMA_CHECK not set", given != null && !given.trim().isEmpty());
        List<File> files = new ArrayList<>();
        for (String path : given.split(File.pathSeparator)) {
            if (path.trim().isEmpty())
                continue;
            File f = new File(path.trim());
            assertTrue("Not found: " + f.getAbsolutePath(), f.exists());
            collect(f, files);
        }
        assertTrue("No .phyphox files in " + given, !files.isEmpty());
        List<String> failures = check(files, "given");
        assertTrue(failures.size() + " of " + files.size() + " files do not load:\n" + String.join("\n", failures), failures.isEmpty());
    }

    //A file named directly is always checked, in a folder only .phyphox files are
    private static void collect(File f, List<File> out) {
        if (f.isFile()) {
            out.add(f);
        } else if (f.isDirectory()) {
            File[] entries = f.listFiles();
            if (entries == null)
                return;
            java.util.Arrays.sort(entries);
            for (File e : entries) {
                if (e.isDirectory() || e.getName().toLowerCase().endsWith(".phyphox") || e.getName().toLowerCase().endsWith(".xml"))
                    collect(e, out);
            }
        }
    }

    private static List<String> check(List<File> files, String set) throws Exception {
        Experiment activity = CorpusTestEnvironment.fullyEquippedActivity(true);
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (File f : files) {
            //Only for the files shipped with the app: this one writes the complete camera metadata into
            //its request, which the simulated phone cannot produce (Robolectric's camera has no key tables)
            if (set.equals("bundled") && f.getName().equals("sensordb.phyphox")) {
                String line = "SKIP  " + f.getPath() + "  (needs real camera metadata, check it on a device)";
                report.append(line).append("\n");
                System.out.println("EXPCHECK " + line);
                continue;
            }
            PhyphoxExperiment e = CorpusTestEnvironment.load(f, activity);
            String line;
            if (e.loaded) {
                line = "OK    " + f.getPath();
            } else {
                String hint = "";
                String name = ContainerRepair.undeclared(e.message);
                if (name != null)
                    hint = "  (hint: declare <container>" + name + "</container> in <data-containers>)";
                line = "FAIL  " + f.getPath() + "\n      " + e.message + hint;
                failures.add(line);
            }
            report.append(line).append('\n');
            System.out.println("EXPCHECK " + line);
        }
        int skipped = report.toString().split("SKIP  ", -1).length - 1;
        report.append("\n").append(files.size() - failures.size() - skipped).append(" of ").append(files.size()).append(" load");
        if (skipped > 0)
            report.append(", ").append(skipped).append(" skipped");
        File out = new File("build/experiment-check-" + set + ".txt");
        out.getAbsoluteFile().getParentFile().mkdirs();
        Files.write(out.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        return failures;
    }
}

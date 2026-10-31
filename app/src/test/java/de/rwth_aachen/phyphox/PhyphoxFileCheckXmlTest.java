package de.rwth_aachen.phyphox;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

//The AI tab hands every model-written file to PhyphoxFile.checkXml before saving it
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PhyphoxFileCheckXmlTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private static final String HEAD = "<phyphox version=\"1.20\"><title>T</title><category>C</category>"
            + "<description>d</description><data-containers><container size=\"0\">a</container>"
            + "<container size=\"0\">t</container></data-containers>";

    private static final String VALID = HEAD
            + "<input><sensor type=\"accelerometer\" ignoreUnavailable=\"true\"><output component=\"abs\">a</output>"
            + "<output component=\"t\">t</output></sensor></input>"
            + "<views><view label=\"v\"><graph><input axis=\"x\">t</input><input axis=\"y\">a</input></graph></view></views>"
            + "<export><set name=\"s\"><data name=\"a\">a</data></set></export></phyphox>";

    @Test
    public void validExperimentPasses() {
        assertNull(PhyphoxFile.checkXml(context, VALID));
    }

    @Test
    public void undeclaredContainerIsReportedByName() {
        String message = PhyphoxFile.checkXml(context, VALID.replace("<output component=\"t\">t</output>", "<output component=\"t\">nope</output>"));
        assertNotNull(message);
        assertTrue(message, message.contains("nope"));
    }

    @Test
    public void brokenXmlIsReported() {
        assertNotNull(PhyphoxFile.checkXml(context, "<phyphox version=\"1.20\"><title>T"));
    }

    @Test
    public void sensorsAndPermissionsOfThisPhoneDoNotMatter() {
        //A validation host has been granted no permissions; the file must still be judged on its syntax
        String microphone = HEAD + "<input><audio rate=\"48000\"><output component=\"out\">a</output></audio></input>"
                + "<views><view label=\"v\"><value><input>a</input></value></view></views><export/></phyphox>";
        assertNull(PhyphoxFile.checkXml(context, microphone));
    }

    @Test
    public void unknownModuleOutputNameIsReported() {
        String bad = HEAD + "<analysis><max><input as=\"y\">a</input><output as=\"maximum\">t</output></max></analysis>"
                + "<views><view label=\"v\"><value><input>t</input></value></view></views></phyphox>";
        assertNotNull(PhyphoxFile.checkXml(context, bad));
    }
}

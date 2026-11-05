package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AiPromptTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void newExperimentPromptCarriesTheExamplesAndNoEditInstructions() throws Exception {
        String prompt = AiPrompt.build(context, null, null);
        assertTrue(prompt.contains("# More working examples"));
        assertTrue(prompt.contains("<title>Pitch finder</title>"));
        assertTrue(prompt.contains("<title>Light alarm</title>"));
        assertTrue(prompt.contains("Create a NEW experiment"));
        assertFalse(prompt.contains("SEARCH"));
    }

    @Test
    public void modifyPromptCarriesTheFileAndTheEditFormat() throws Exception {
        String prompt = AiPrompt.build(context, "My pendulum", "<phyphox>\n  <title>My pendulum</title>\n</phyphox>");
        assertTrue(prompt.contains("MODIFY the existing experiment \"My pendulum\""));
        assertTrue(prompt.contains("<<<<<<< SEARCH"));
        assertTrue(prompt.contains("This is the current file of \"My pendulum\""));
        assertTrue(prompt.contains("  <title>My pendulum</title>\n</phyphox>\n```"));
        assertFalse(prompt.contains("{{"));
    }

    //Everything before "# This phone" must not depend on the phone or the task: the service caches
    //a repeated prefix, which makes every request after the first one cheap.
    @Test
    public void startOfThePromptIsTheSameForEveryRequest() throws Exception {
        String a = AiPrompt.build(context, null, null);
        String b = AiPrompt.build(context, "X", "<phyphox></phyphox>");
        String headA = a.substring(0, a.indexOf("# This phone"));
        String headB = b.substring(0, b.indexOf("# This phone"));
        assertEquals(headA, headB);
        assertTrue(headA.length() > 20000);
    }
}

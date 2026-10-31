package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AiSessionTest {
    private static String block(String body) {
        return "```xml\n<phyphox version=\"1.20\">" + body + "</phyphox>\n```";
    }

    //Answers from a script; remembers what it was asked
    private static final class Script implements AiSession.Backend {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<JSONArray> asked = new ArrayList<>();

        Script(Object... answers) {
            for (Object a : answers)
                this.answers.add(a);
        }

        @Override
        public String complete(JSONArray messages) throws IOException {
            asked.add(messages);
            Object next = answers.poll();
            if (next instanceof IOException)
                throw (IOException) next;
            return (String) next;
        }
    }

    @Test
    public void validFileIsReturnedWithoutFixes() throws Exception {
        AiSession session = new AiSession(new Script("Done.\n" + block("a")), xml -> null, "sys");
        AiSession.Turn turn = session.send("make it");
        assertEquals(0, turn.fixes);
        assertNotNull(turn.xml);
        assertNull(turn.error);
        assertEquals("Done.", turn.prose);
    }

    @Test
    public void parserMessageGoesBackToTheModel() throws Exception {
        Script script = new Script(block("bad"), block("good"));
        AiSession session = new AiSession(script, xml -> xml.contains("bad") ? "Buffer \"x\" not defined." : null, "sys");
        AiSession.Turn turn = session.send("make it");
        assertEquals(1, turn.fixes);
        assertTrue(turn.xml.contains("good"));
        JSONArray second = script.asked.get(1);
        String last = second.getJSONObject(second.length() - 1).getString("content");
        assertTrue(last.contains("Buffer \"x\" not defined."));
        assertEquals("user", second.getJSONObject(second.length() - 1).getString("role"));
    }

    @Test
    public void givesUpAfterTwoCorrections() throws Exception {
        Script script = new Script(block("1"), block("2"), block("3"), block("never"));
        AiSession session = new AiSession(script, xml -> "still broken", "sys");
        AiSession.Turn turn = session.send("make it");
        assertNull(turn.xml);
        assertEquals("still broken", turn.error);
        assertEquals(AiSession.MAX_FIXES, turn.fixes);
        assertEquals(3, script.asked.size());
    }

    @Test
    public void truncatedAnswerIsAskedAgainWithoutTheParser() throws Exception {
        Script script = new Script("```xml\n<phyphox version=\"1.20\"><title>", block("whole"));
        final int[] checks = {0};
        AiSession session = new AiSession(script, xml -> {
            checks[0]++;
            return null;
        }, "sys");
        AiSession.Turn turn = session.send("make it");
        assertEquals(1, turn.fixes);
        assertEquals(1, checks[0]);
        assertTrue(turn.xml.contains("whole"));
    }

    @Test
    public void questionNeedsNoFile() throws Exception {
        AiSession session = new AiSession(new Script("Which sensor?"), xml -> {
            fail("nothing to check");
            return null;
        }, "sys");
        AiSession.Turn turn = session.send("hm");
        assertNull(turn.xml);
        assertNull(turn.error);
        assertEquals("Which sensor?", turn.prose);
    }

    @Test
    public void failedRequestLeavesTheConversationUntouched() throws Exception {
        Script script = new Script(new IOException("offline"), "Sure.");
        AiSession session = new AiSession(script, xml -> null, "sys");
        try {
            session.send("first");
            fail("expected an IOException");
        } catch (IOException expected) {
            assertEquals("offline", expected.getMessage());
        }
        session.send("first again");
        JSONArray messages = script.asked.get(1);
        //system + the single user message: nothing of the failed attempt remains
        assertEquals(2, messages.length());
        assertEquals("first again", messages.getJSONObject(1).getString("content"));
    }

    @Test
    public void historyStartsWithAUserMessageAndIsCapped() throws Exception {
        Script script = new Script();
        for (int i = 0; i < 30; i++)
            script.answers.add("ok " + i);
        AiSession session = new AiSession(script, xml -> null, "sys");
        for (int i = 0; i < 30; i++)
            session.send("q" + i);
        JSONArray last = script.asked.get(29);
        assertEquals("system", last.getJSONObject(0).getString("role"));
        assertEquals("user", last.getJSONObject(1).getString("role"));
        assertTrue(last.length() <= 25);
    }

    @Test
    public void resetForgetsAndSetSystemSwitchesTheInstructions() throws Exception {
        Script script = new Script("a", "b");
        AiSession session = new AiSession(script, xml -> null, "one");
        session.send("hello");
        session.reset();
        session.setSystem("two");
        session.send("again");
        JSONArray messages = script.asked.get(1);
        assertEquals(2, messages.length());
        assertEquals("two", messages.getJSONObject(0).getString("content"));
    }
}

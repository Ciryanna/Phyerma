package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiReplyTest {
    private static final String FILE = "<phyphox version=\"1.20\">\n  <title>T</title>\n</phyphox>";

    @Test
    public void splitsProseFromTheFile() {
        AiReply reply = AiReply.parse("Here you go.\n```xml\n" + FILE + "\n```\nShake it.");
        assertEquals(FILE, reply.xml);
        assertFalse(reply.truncated);
        assertTrue(reply.prose.contains("Here you go."));
        assertTrue(reply.prose.contains("Shake it."));
        assertFalse(reply.prose.contains("<phyphox"));
    }

    @Test
    public void answerWithoutFileIsJustProse() {
        AiReply reply = AiReply.parse("Which sensor do you mean?");
        assertNull(reply.xml);
        assertFalse(reply.truncated);
        assertEquals("Which sensor do you mean?", reply.prose);
    }

    @Test
    public void nullAndEmptyAreHarmless() {
        assertNull(AiReply.parse(null).xml);
        assertEquals("", AiReply.parse("").prose);
    }

    @Test
    public void lastBlockWithAFileWins() {
        String text = "```xml\n<phyphox><title>old</title></phyphox>\n```\nbetter:\n```xml\n" + FILE + "\n```";
        assertEquals(FILE, AiReply.parse(text).xml);
    }

    @Test
    public void blocksWithoutAFileAreIgnored() {
        String text = "```xml\n<container>x</container>\n```\n```xml\n" + FILE + "\n```";
        assertEquals(FILE, AiReply.parse(text).xml);
    }

    @Test
    public void unterminatedBlockIsTruncated() {
        AiReply reply = AiReply.parse("Sure.\n```xml\n<phyphox version=\"1.20\">\n  <title>T</title>\n  <data-cont");
        assertTrue(reply.truncated);
        assertTrue(reply.xml.startsWith("<phyphox"));
        assertEquals("Sure.", reply.prose);
    }

    @Test
    public void windowsLineEndingsAreAccepted() {
        String windows = FILE.replace("\n", "\r\n");
        assertEquals(windows, AiReply.parse("```xml\r\n" + windows + "\r\n```").xml);
    }
}

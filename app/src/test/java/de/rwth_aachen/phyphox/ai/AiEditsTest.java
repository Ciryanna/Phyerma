package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class AiEditsTest {
    private static final String FILE = "<phyphox>\n  <title>Old</title>\n  <data-containers>\n    <container>a</container>\n    <container>b</container>\n  </data-containers>\n</phyphox>\n";

    private static String edit(String search, String replace) {
        return "```edit\n<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n```";
    }

    private static String applied(String answer) throws Exception {
        AiReply reply = AiReply.parse(answer);
        return AiEdits.apply(FILE, reply.edits);
    }

    @Test
    public void replacesExactText() throws Exception {
        assertTrue(applied(edit("  <title>Old</title>", "  <title>New</title>")).contains("<title>New</title>"));
    }

    @Test
    public void everythingElseStaysUntouched() throws Exception {
        String out = applied(edit("<title>Old</title>", "<title>New</title>"));
        assertEquals(FILE.replace("Old", "New"), out);
    }

    @Test
    public void insertsByRepeatingTheAnchorLine() throws Exception {
        String out = applied(edit("    <container>b</container>", "    <container>b</container>\n    <container>c</container>"));
        assertTrue(out.contains("<container>b</container>\n    <container>c</container>\n  </data-containers>"));
    }

    @Test
    public void emptyReplacementDeletes() throws Exception {
        String out = applied("<<<<<<< SEARCH\n    <container>a</container>\n=======\n>>>>>>> REPLACE\n");
        assertFalse(out.contains(">a<"));
        assertTrue(out.contains(">b<"));
    }

    @Test
    public void severalBlocksApplyInOrder() throws Exception {
        String answer = "Done.\n```edit\n<<<<<<< SEARCH\n<title>Old</title>\n=======\n<title>Mid</title>\n>>>>>>> REPLACE\n"
                + "<<<<<<< SEARCH\n<title>Mid</title>\n=======\n<title>New</title>\n>>>>>>> REPLACE\n```\nThe title changed.";
        AiReply reply = AiReply.parse(answer);
        assertEquals(2, reply.edits.size());
        assertTrue(AiEdits.apply(FILE, reply.edits).contains("<title>New</title>"));
        assertEquals("Done.\n\nThe title changed.", reply.prose);
    }

    @Test
    public void indentationMayDiffer() throws Exception {
        String out = applied(edit("<container>a</container>", "    <container>z</container>"));
        assertTrue(out.contains("<container>z</container>"));
        assertFalse(out.contains(">a<"));
    }

    @Test
    public void windowsLineEndingsAreFine() throws Exception {
        String out = AiEdits.apply(FILE.replace("\n", "\r\n"), AiReply.parse(edit("<title>Old</title>", "<title>New</title>")).edits);
        assertTrue(out.contains("<title>New</title>"));
    }

    @Test
    public void missingTextIsReportedWithItsFirstLine() throws Exception {
        try {
            applied(edit("<title>Nothing</title>", "x"));
            fail();
        } catch (AiEdits.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Edit 1"));
            assertTrue(e.getMessage(), e.getMessage().contains("<title>Nothing</title>"));
        }
    }

    @Test
    public void ambiguousTextIsRefused() throws Exception {
        try {
            applied(edit("<container>", "x"));
            fail();
        } catch (AiEdits.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("more than once"));
        }
    }

    @Test
    public void separatorInsideTheReplacementMeansTheBlockIsBroken() throws Exception {
        String answer = "<<<<<<< SEARCH\n<title>Old</title>\n=======\n<title>New</title>\n=======\n<title>Newer</title>\n>>>>>>> REPLACE\n";
        try {
            AiEdits.apply(FILE, AiReply.parse(answer).edits);
            fail();
        } catch (AiEdits.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("malformed"));
        }
    }

    @Test
    public void emptySearchIsRefused() throws Exception {
        try {
            applied("<<<<<<< SEARCH\n\n=======\nx\n>>>>>>> REPLACE\n");
            fail();
        } catch (AiEdits.Failure e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void failureNamesTheFailingBlock() throws Exception {
        String answer = "<<<<<<< SEARCH\n<title>Old</title>\n=======\n<title>New</title>\n>>>>>>> REPLACE\n"
                + "<<<<<<< SEARCH\nnot there\n=======\nx\n>>>>>>> REPLACE\n";
        try {
            AiEdits.apply(FILE, AiReply.parse(answer).edits);
            fail();
        } catch (AiEdits.Failure e) {
            assertTrue(e.getMessage().startsWith("Edit 2"));
        }
    }

    @Test
    public void replacementMayContainDollarSignsAndBackslashes() throws Exception {
        assertTrue(applied(edit("<title>Old</title>", "<title>$1 \\n</title>")).contains("<title>$1 \\n</title>"));
    }

    @Test
    public void editBlockWithAPhyphoxLineIsNotTakenForAFile() {
        AiReply reply = AiReply.parse(edit("<phyphox>", "<phyphox version=\"1.20\">"));
        assertNull(reply.xml);
        List<AiEdits.Edit> edits = reply.edits;
        assertEquals(1, edits.size());
    }

    @Test
    public void cutOffEditIsTruncated() {
        AiReply reply = AiReply.parse("Sure.\n```edit\n<<<<<<< SEARCH\n<title>Old</title>\n=======\n<title>Ne");
        assertTrue(reply.truncated);
        assertTrue(reply.edits.isEmpty());
        assertEquals("Sure.", reply.prose);
    }
}

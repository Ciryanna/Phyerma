package de.rwth_aachen.phyphox.ai;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//A model answer split into what to show the user and the experiment file it carries, if any.
public final class AiReply {
    //The text without the code block
    public final String prose;
    //The complete file, or null if the answer carried none
    public final String xml;
    //The answer ended inside a code block (token limit): there is a file, but it is not complete
    public final boolean truncated;

    //SEARCH/REPLACE blocks that change the experiment being modified; empty if the answer had none
    public final List<AiEdits.Edit> edits;

    private AiReply(String prose, String xml, boolean truncated) {
        this(prose, xml, truncated, Collections.<AiEdits.Edit>emptyList());
    }

    private AiReply(String prose, String xml, boolean truncated, List<AiEdits.Edit> edits) {
        this.prose = prose;
        this.xml = xml;
        this.truncated = truncated;
        this.edits = edits;
    }

    private static final Pattern EMPTY_FENCE = Pattern.compile("```[A-Za-z]*[ \t]*\r?\n\\s*```");
    private static final Pattern TAIL_FENCE = Pattern.compile("```[A-Za-z]*\\s*$");
    private static final Pattern FENCE = Pattern.compile("```[ \t]*(?:xml|XML|phyphox)?[ \t]*\r?\n(.*?)```", Pattern.DOTALL);
    private static final Pattern OPEN_FENCE = Pattern.compile("```[ \t]*(?:xml|XML|phyphox)?[ \t]*\r?\n");

    public static AiReply parse(String text) {
        if (text == null)
            text = "";
        //Edit blocks take precedence: their text may well contain a "<phyphox" line
        int started = AiEdits.started(text);
        if (started > 0) {
            List<AiEdits.Edit> edits = AiEdits.parse(text);
            if (edits.size() < started) {
                String before = text.substring(0, AiEdits.firstStart(text));
                return new AiReply(TAIL_FENCE.matcher(before).replaceAll("").trim(), null, true);
            }
            String prose = EMPTY_FENCE.matcher(AiEdits.blocks(text).replaceAll("")).replaceAll("").trim();
            return new AiReply(prose, null, false, edits);
        }
        //The last block that holds a phyphox file wins, should the model send more than one
        Matcher m = FENCE.matcher(text);
        int start = -1, end = -1;
        String xml = null;
        while (m.find()) {
            if (m.group(1).contains("<phyphox")) {
                xml = m.group(1).trim();
                start = m.start();
                end = m.end();
            }
        }
        if (xml != null)
            return new AiReply((text.substring(0, start) + text.substring(end)).trim(), xml, false);

        //No closed block. If a block was opened and never closed, the answer was cut off.
        Matcher open = OPEN_FENCE.matcher(text);
        if (open.find() && text.indexOf("<phyphox", open.end()) >= 0) {
            return new AiReply(text.substring(0, open.start()).trim(), text.substring(open.end()).trim(), true);
        }
        return new AiReply(text.trim(), null, false);
    }
}

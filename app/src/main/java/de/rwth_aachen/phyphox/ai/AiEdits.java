package de.rwth_aachen.phyphox.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Small changes to an existing experiment, written by the model as SEARCH/REPLACE blocks:
//
//<<<<<<< SEARCH
//lines that exist in the file
//=======
//the lines that take their place
//>>>>>>> REPLACE
//
//Much cheaper and safer than sending the whole file again: everything not named stays byte for byte.
public final class AiEdits {
    private AiEdits() {}

    public static final class Edit {
        public final String search;
        public final String replace;

        Edit(String search, String replace) {
            this.search = search;
            this.replace = replace;
        }
    }

    //An edit that cannot be applied; the message is written for the model
    public static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static final Pattern BLOCK = Pattern.compile(
            "<<<<<<<[ \t]*SEARCH[ \t]*\r?\n(.*?)\r?\n?=======[ \t]*\r?\n(.*?)\r?\n?>>>>>>>[ \t]*REPLACE[ \t]*(?:\r?\n|$)",
            Pattern.DOTALL);

    //Number of edit blocks that were started, complete or not
    static int started(String text) {
        int count = 0;
        Matcher m = Pattern.compile("<<<<<<<[ \t]*SEARCH").matcher(text);
        while (m.find())
            count++;
        return count;
    }

    static int firstStart(String text) {
        Matcher m = Pattern.compile("<<<<<<<[ 	]*SEARCH").matcher(text);
        return m.find() ? m.start() : text.length();
    }

    static Matcher blocks(String text) {
        return BLOCK.matcher(text);
    }

    static List<Edit> parse(String text) {
        List<Edit> edits = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find())
            edits.add(new Edit(m.group(1), m.group(2)));
        return edits;
    }

    //The file with all edits applied in order. Each SEARCH has to match exactly one place; it may
    //differ from the file in indentation and line endings only.
    public static String apply(String file, List<Edit> edits) throws Failure {
        String text = file.replace("\r\n", "\n");
        int number = 0;
        for (Edit edit : edits) {
            number++;
            String search = edit.search.replace("\r\n", "\n");
            String replace = edit.replace.replace("\r\n", "\n");
            if (hasMarker(search) || hasMarker(replace))
                throw new Failure("Edit " + number + " is malformed: a block is exactly <<<<<<< SEARCH, the old lines, =======, the new lines, >>>>>>> REPLACE, and the marker lines must not appear inside the text.");
            if (search.trim().isEmpty())
                throw new Failure("Edit " + number + ": the SEARCH part is empty. Copy at least one existing line of the file into it.");
            int at = text.indexOf(search);
            int length = search.length();
            if (at >= 0 && text.indexOf(search, at + 1) >= 0)
                throw new Failure("Edit " + number + ": the SEARCH text appears more than once in the file. Include more of the surrounding lines so it is unique.");
            if (at < 0) {
                int[] span = looseMatch(text, search, number);
                at = span[0];
                length = span[1];
            }
            text = text.substring(0, at) + replace + text.substring(at + length);
        }
        return text;
    }

    private static boolean hasMarker(String text) {
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.equals("=======") || t.startsWith("<<<<<<<") || t.startsWith(">>>>>>>"))
                return true;
        }
        return false;
    }

    //Same lines, compared without leading and trailing blanks. Returns {start, length} in text.
    private static int[] looseMatch(String text, String search, int number) throws Failure {
        String[] wanted = trimmedLines(search);
        String[] have = text.split("\n", -1);
        int found = -1;
        for (int i = 0; i + wanted.length <= have.length; i++) {
            boolean same = true;
            for (int j = 0; j < wanted.length && same; j++)
                same = have[i + j].trim().equals(wanted[j]);
            if (same) {
                if (found >= 0)
                    throw new Failure("Edit " + number + ": the SEARCH text appears more than once in the file. Include more of the surrounding lines so it is unique.");
                found = i;
            }
        }
        if (found < 0) {
            String first = wanted.length > 0 ? wanted[0] : "";
            throw new Failure("Edit " + number + ": the SEARCH text was not found in the file (it has to be copied exactly from the current file; the first line was \""
                    + (first.length() > 80 ? first.substring(0, 80) + "..." : first) + "\").");
        }
        int start = 0;
        for (int i = 0; i < found; i++)
            start += have[i].length() + 1;
        int end = start;
        for (int j = 0; j < wanted.length; j++)
            end += have[found + j].length() + (j < wanted.length - 1 ? 1 : 0);
        return new int[]{start, end - start};
    }

    private static String[] trimmedLines(String search) {
        String[] lines = search.split("\n", -1);
        int from = 0, to = lines.length;
        while (from < to && lines[from].trim().isEmpty())
            from++;
        while (to > from && lines[to - 1].trim().isEmpty())
            to--;
        String[] out = new String[to - from];
        for (int i = from; i < to; i++)
            out[i - from] = lines[i].trim();
        return out;
    }
}

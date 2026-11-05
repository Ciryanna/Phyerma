package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ContainerRepairTest {
    @Test
    public void picksTheNameOutOfTheParserMessage() {
        assertEquals("peak", ContainerRepair.undeclared("Line 33: Buffer \"peak\" not defined."));
        assertNull(ContainerRepair.undeclared("Line 3: Something else."));
        assertNull(ContainerRepair.undeclared(null));
    }

    @Test
    public void numbersAndOddNamesAreLeftToTheModel() {
        assertNull(ContainerRepair.undeclared("Buffer \"0.5\" not defined."));
        assertNull(ContainerRepair.undeclared("Buffer \"a b\" not defined."));
    }

    @Test
    public void declaresAfterTheOpeningTag() {
        String out = ContainerRepair.declare("<phyphox>\n  <data-containers>\n    <container>a</container>\n  </data-containers>\n</phyphox>", "peak");
        assertTrue(out, out.contains("<data-containers>\n    <container size=\"0\">peak</container>\n    <container>a</container>"));
    }

    @Test
    public void noSectionNoRepair() {
        assertNull(ContainerRepair.declare("<phyphox></phyphox>", "peak"));
    }

    //A validator that knows only the declared names: the session patches the file until it passes
    private static String check(String xml) {
        Set<String> declared = new HashSet<>();
        Matcher d = Pattern.compile("<container[^>]*>([^<]+)</container>").matcher(xml);
        while (d.find())
            declared.add(d.group(1));
        Matcher u = Pattern.compile("<output>([^<]+)</output>").matcher(xml);
        while (u.find())
            if (!declared.contains(u.group(1)))
                return "Line 9: Buffer \"" + u.group(1) + "\" not defined.";
        return null;
    }

    @Test
    public void sessionDeclaresMissingContainersWithoutBotheringTheModel() throws Exception {
        String file = "<phyphox>\n  <data-containers>\n    <container>a</container>\n  </data-containers>\n"
                + "  <analysis><x><output>b</output></x><x><output>c</output></x></analysis>\n</phyphox>";
        final int[] asked = {0};
        AiSession session = new AiSession((JSONArray messages) -> {
            asked[0]++;
            return "```xml\n" + file + "\n```";
        }, ContainerRepairTest::check, "sys");
        AiSession.Turn turn = session.send("make it");
        assertEquals(0, turn.fixes);
        assertEquals(1, asked[0]);
        assertTrue(turn.xml, turn.xml.contains(">b</container>") && turn.xml.contains(">c</container>"));
    }
}

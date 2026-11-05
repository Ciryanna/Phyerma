package de.rwth_aachen.phyphox.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

//The commonest mistake of the model is using a container name it never declared. The parser says
//exactly which one, and the cure is mechanical, so it is applied here instead of costing a round
//trip to the model.
public final class ContainerRepair {
    private ContainerRepair() {}

    private static final Pattern UNDEFINED = Pattern.compile("Buffer \"([^\"]+)\" not defined");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECTION = Pattern.compile("<data-containers\\s*>");

    //The container name the parser complained about, or null if the message is about something else
    //or the name is no sensible container name (a number the model wrote where it needed type="value")
    public static String undeclared(String parserMessage) {
        if (parserMessage == null)
            return null;
        Matcher m = UNDEFINED.matcher(parserMessage);
        if (!m.find() || !IDENTIFIER.matcher(m.group(1)).matches())
            return null;
        return m.group(1);
    }

    //The file with the container declared, or null if there is no place to declare it. Unlimited
    //size, so that nothing the experiment writes into it is cut off.
    public static String declare(String xml, String name) {
        Matcher m = SECTION.matcher(xml);
        if (!m.find())
            return null;
        return xml.substring(0, m.end()) + "\n    <container size=\"0\">" + name + "</container>" + xml.substring(m.end());
    }
}

package de.rwth_aachen.phyphox.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

//One conversation with the model. A file in an answer is checked with the real parser before it is
//handed back; if the parser refuses it, the parser's message goes back to the model, which gets
//up to MAX_FIXES chances to correct its own file without the user having to do anything.
public final class AiSession {
    public interface Backend {
        String complete(JSONArray messages) throws IOException;
    }

    public interface Validator {
        //null if the file loads, otherwise the parser's message
        String check(String xml);
    }

    //What one user message led to
    public static final class Turn {
        public final String prose;
        //A file that passed the parser, or null
        public final String xml;
        //Why no file could be produced even after the fixing rounds, or null
        public final String error;
        //How many times the model had to correct the file
        public final int fixes;

        Turn(String prose, String xml, String error, int fixes) {
            this.prose = prose;
            this.xml = xml;
            this.error = error;
            this.fixes = fixes;
        }
    }

    static final int MAX_FIXES = 2;
    private static final int MAX_HISTORY = 24;

    private final Backend backend;
    private final Validator validator;
    private String system;
    private final List<String[]> history = new ArrayList<>();

    public AiSession(Backend backend, Validator validator, String system) {
        this.backend = backend;
        this.validator = validator;
        this.system = system;
    }

    //The system message carries the target experiment, so it changes when the user picks another one
    public void setSystem(String system) {
        this.system = system;
    }

    public void reset() {
        history.clear();
    }

    //If the service cannot be reached, the conversation stays as it was before this message, so the
    //user can simply send it again.
    public Turn send(String userText) throws IOException {
        List<String[]> before = new ArrayList<>(history);
        try {
            return converse(userText);
        } catch (IOException e) {
            history.clear();
            history.addAll(before);
            throw e;
        }
    }

    private Turn converse(String userText) throws IOException {
        add("user", userText);
        int fixes = 0;
        String lastProblem = null;
        String prose = "";
        while (true) {
            String raw = backend.complete(messages());
            add("assistant", raw);
            AiReply reply = AiReply.parse(raw);
            prose = reply.prose;
            if (reply.xml == null)
                return new Turn(prose, null, lastProblem, fixes);
            String problem = reply.truncated
                    ? "Your answer was cut off before the file ended. Send the complete file again, and keep it shorter."
                    : validator.check(reply.xml);
            if (problem == null)
                return new Turn(prose, reply.xml, null, fixes);
            lastProblem = problem;
            if (fixes == MAX_FIXES)
                return new Turn(prose, null, problem, fixes);
            fixes++;
            add("user", reply.truncated ? problem
                    : "The app could not load that experiment: " + problem
                    + "\nCorrect the file and send the complete corrected file in one xml block.");
        }
    }

    private void add(String role, String content) {
        history.add(new String[]{role, content});
        while (history.size() > MAX_HISTORY)
            history.remove(0);
    }

    private JSONArray messages() throws IOException {
        try {
            JSONArray array = new JSONArray();
            array.put(new JSONObject().put("role", "system").put("content", system));
            //The history must start with a user message
            int from = 0;
            while (from < history.size() && !history.get(from)[0].equals("user"))
                from++;
            for (int i = from; i < history.size(); i++)
                array.put(new JSONObject().put("role", history.get(i)[0]).put("content", history.get(i)[1]));
            return array;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}

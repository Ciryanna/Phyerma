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
    //The file the model is asked to change; edit blocks are applied to it. null when creating.
    private String base;
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

    public void setBase(String xml) {
        this.base = xml;
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
        while (true) {
            String raw = backend.complete(messages());
            add("assistant", raw);
            AiReply reply = AiReply.parse(raw);
            String prose = reply.prose;
            if (reply.xml == null && reply.edits.isEmpty() && !reply.truncated)
                return new Turn(prose, null, lastProblem, fixes);

            String xml = reply.xml;
            String problem = null;
            String reason = null; //the short form that is shown to the user if the fixing rounds run out
            if (reply.truncated) {
                reason = "The answer was cut off.";
                problem = "Your answer was cut off before the end. Send it again, and keep it shorter.";
            } else {
                boolean edited = !reply.edits.isEmpty();
                if (edited) {
                    if (base == null) {
                        reason = "There is no existing experiment to edit.";
                        problem = "There is no existing experiment to edit, so edit blocks cannot be used. Send the complete file in one xml block.";
                    } else {
                        try {
                            xml = AiEdits.apply(base, reply.edits);
                        } catch (AiEdits.Failure failure) {
                            reason = failure.getMessage();
                            problem = failure.getMessage() + " Nothing was changed. Send corrected edit blocks, still written against the ORIGINAL file.";
                        }
                    }
                }
                if (problem == null) {
                    String refused = validator.check(xml);
                    //Undeclared containers are declared here; every pass fixes one name, and a file
                    //that misses more than a dozen is not worth patching.
                    String declared = null;
                    for (int i = 0; i < 12; i++) {
                        String name = ContainerRepair.undeclared(refused);
                        String patched = name == null || name.equals(declared) ? null : ContainerRepair.declare(xml, name);
                        if (patched == null)
                            break;
                        declared = name;
                        xml = patched;
                        refused = validator.check(xml);
                    }
                    if (refused != null) {
                        reason = refused;
                        problem = edited
                                ? "The app could not load the experiment after applying your edits: " + refused
                                + "\nEdits are always applied to the ORIGINAL file again, so send corrected edit blocks relative to the original (or the complete file in one xml block)."
                                : "The app could not load that experiment: " + refused
                                + "\nCorrect the file and send the complete corrected file in one xml block.";
                    }
                }
            }
            if (problem == null)
                return new Turn(prose, xml, null, fixes);
            lastProblem = reason;
            if (fixes == MAX_FIXES)
                return new Turn(prose, null, reason, fixes);
            fixes++;
            add("user", problem);
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

package de.rwth_aachen.phyphox.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.StringRes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import de.rwth_aachen.phyphox.PhyphoxFile;
import de.rwth_aachen.phyphox.R;

//Everything the AI tab does apart from drawing it. The conversation lives here, not in the
//activity, because the bottom navigation finishes the activity when the user switches tab and
//the chat (and a request that is still running) has to survive that. All state is touched on the
//main thread; the network call and the model's corrections run on one worker thread.
public final class AiController {
    public enum Kind {USER, BOT, NOTE, ERROR, RESULT}

    //One line of the chat. Text from the app is a string resource, resolved when it is drawn, so it
    //follows the language the user has selected at that time.
    public static final class Entry {
        public final Kind kind;
        public final String text;
        @StringRes
        public final int resId;
        public final Object[] args;
        //RESULT only: the experiment that was written and how to take it back
        public final String file;
        final ExperimentStore.Change change;
        final ExperimentStore.Ref previousTarget;
        public boolean undone;

        Entry(Kind kind, String text, int resId, Object[] args, String file,
              ExperimentStore.Change change, ExperimentStore.Ref previousTarget) {
            this.kind = kind;
            this.text = text;
            this.resId = resId;
            this.args = args;
            this.file = file;
            this.change = change;
            this.previousTarget = previousTarget;
        }

        public String resolve(Context context) {
            return resId == 0 ? text : context.getString(resId, args);
        }
    }

    public interface Listener {
        void onChanged();
    }

    private static final AiController INSTANCE = new AiController();

    public static AiController get() {
        return INSTANCE;
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private AiSession session;
    private ExperimentStore.Ref target; //null: create a new experiment
    private boolean busy;

    private AiController() {}

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public List<Entry> entries() {
        return entries;
    }

    public ExperimentStore.Ref target() {
        return target;
    }

    public boolean busy() {
        return busy;
    }

    private void add(Entry entry) {
        entries.add(entry);
        changed();
    }

    private void note(Kind kind, @StringRes int resId, Object... args) {
        add(new Entry(kind, null, resId, args, null, null, null));
    }

    private void changed() {
        if (listener != null)
            listener.onChanged();
    }

    //Choose what the next messages are about; null means a new experiment. The conversation starts
    //over, because the model gets the target's file as part of its instructions.
    public void selectTarget(Context context, ExperimentStore.Ref ref) {
        if (busy)
            return;
        appContext = context.getApplicationContext();
        try {
            setTarget(appContext, ref);
        } catch (IOException e) {
            note(Kind.ERROR, R.string.phyerma_ai_err_read, e.getMessage());
            return;
        }
        session.reset();
        note(Kind.NOTE, ref == null ? R.string.phyerma_ai_now_new : R.string.phyerma_ai_now_changing, ref == null ? "" : ref.title);
    }

    //Point the session at a target and give it the matching instructions
    private void setTarget(Context context, ExperimentStore.Ref ref) throws IOException {
        String xml = ref == null ? null : ExperimentStore.read(context, ref);
        String system = AiPrompt.build(context, ref == null ? null : ref.title, xml);
        if (session == null) {
            final Context app = context;
            session = new AiSession(
                    messages -> DeepSeekClient.chat(AiKeyStore.get(app), messages),
                    this::checkOnMain,
                    system);
        } else {
            session.setSystem(system);
        }
        target = ref;
    }

    //The parser host is an activity and needs a Looper, so the check runs on the main thread while
    //the worker waits for it.
    private String checkOnMain(String xml) {
        if (Looper.myLooper() == Looper.getMainLooper())
            return PhyphoxFile.checkXml(appContext, xml);
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                result.set(PhyphoxFile.checkXml(appContext, xml));
            } catch (RuntimeException e) {
                result.set("Unhandled error while checking the file: " + e);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
        return result.get();
    }

    private Context appContext;

    public void send(Context context, String text) {
        text = text.trim();
        if (busy || text.isEmpty())
            return;
        appContext = context.getApplicationContext();
        if (session == null) {
            try {
                setTarget(appContext, target);
            } catch (IOException e) {
                note(Kind.ERROR, R.string.phyerma_ai_err_read, e.getMessage());
                return;
            }
        }
        add(new Entry(Kind.USER, text, 0, null, null, null, null));
        busy = true;
        changed();
        final String message = text;
        final AiSession current = session;
        worker.execute(() -> {
            AiSession.Turn turn = null;
            IOException failure = null;
            try {
                turn = current.send(message);
            } catch (IOException e) {
                failure = e;
            }
            final AiSession.Turn done = turn;
            final IOException error = failure;
            main.post(() -> finish(done, error));
        });
    }

    private void finish(AiSession.Turn turn, IOException failure) {
        busy = false;
        if (failure != null) {
            reportFailure(failure);
            return;
        }
        if (!turn.prose.isEmpty())
            entries.add(new Entry(Kind.BOT, turn.prose, 0, null, null, null, null));
        if (turn.xml != null) {
            try {
                apply(turn);
            } catch (IOException e) {
                note(Kind.ERROR, R.string.phyerma_ai_err_write, e.getMessage());
            }
        } else if (turn.error != null) {
            note(Kind.ERROR, R.string.phyerma_ai_failed, turn.error);
        }
        changed();
    }

    private void apply(AiSession.Turn turn) throws IOException {
        ExperimentStore.Ref previous = target;
        boolean copy = previous != null && previous.asset;
        ExperimentStore.Change change = previous == null || copy
                ? ExperimentStore.create(appContext, turn.xml)
                : ExperimentStore.replace(appContext, previous, turn.xml);
        String title = ExperimentStore.titleOf(new ByteArrayInputStream(turn.xml.getBytes(StandardCharsets.UTF_8)), change.file.getName());
        int message = previous == null ? R.string.phyerma_ai_saved_new
                : copy ? R.string.phyerma_ai_saved_copy : R.string.phyerma_ai_saved_changed;
        Object[] args = copy ? new Object[]{previous.title, title} : new Object[]{title};
        entries.add(new Entry(Kind.RESULT, null, message, args, change.file.getName(), change, previous));
        if (turn.fixes > 0)
            entries.add(new Entry(Kind.NOTE, null, R.string.phyerma_ai_fixed, new Object[]{turn.fixes}, null, null, null));
        //Follow-up requests are about the experiment that was just written
        try {
            setTarget(appContext, new ExperimentStore.Ref(title, change.file.getName(), false));
        } catch (IOException e) {
            target = null;
        }
    }

    //Only the newest write that is still standing can be taken back; an older one is built upon by
    //the later changes, and undoing it first would leave the files in a state nobody asked for.
    public boolean canUndo(Entry entry) {
        if (entry.kind != Kind.RESULT || entry.undone || entry.change == null)
            return false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry other = entries.get(i);
            if (other.kind == Kind.RESULT && !other.undone)
                return other == entry;
        }
        return false;
    }

    //Take back the write of a RESULT entry
    public void undo(Entry entry) {
        if (busy || !canUndo(entry))
            return;
        try {
            entry.change.undo();
        } catch (IOException e) {
            note(Kind.ERROR, R.string.phyerma_ai_err_write, e.getMessage());
            return;
        }
        entry.undone = true;
        try {
            setTarget(appContext, entry.previousTarget);
        } catch (IOException e) {
            target = null;
        }
        changed();
    }

    private void reportFailure(IOException e) {
        if (e instanceof DeepSeekClient.ApiException) {
            DeepSeekClient.ApiException api = (DeepSeekClient.ApiException) e;
            switch (api.status) {
                case 401:
                    note(Kind.ERROR, R.string.phyerma_ai_err_key);
                    return;
                case 402:
                    note(Kind.ERROR, R.string.phyerma_ai_err_balance);
                    return;
                case 429:
                    note(Kind.ERROR, R.string.phyerma_ai_err_rate);
                    return;
                default:
                    note(Kind.ERROR, R.string.phyerma_ai_err_other, api.status, api.getMessage());
                    return;
            }
        }
        note(Kind.ERROR, R.string.phyerma_ai_err_net, String.valueOf(e.getMessage()));
    }
}

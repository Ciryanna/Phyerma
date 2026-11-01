package de.rwth_aachen.phyphox.ai;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.rwth_aachen.phyphox.ExperimentList.model.Const;
import de.rwth_aachen.phyphox.Experiment;
import de.rwth_aachen.phyphox.R;
import de.rwth_aachen.phyphox.device.BottomNavHelper;

//The third tab: talk (typed or spoken) to the model, which writes a phyphox experiment file that is
//saved into the experiment list, or changes one that the user picked.
public class AiActivity extends AppCompatActivity implements AiController.Listener {
    private final AiController controller = AiController.get();
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView targetView;
    private ActivityResultLauncher<Intent> speech;
    private ActivityResultLauncher<String> micPermission;
    private ImageButton mic;
    private final VoiceRecorder recorder = new VoiceRecorder();
    private boolean transcribing;
    private CharSequence inputHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Phyphox_DayNight);
        setContentView(R.layout.activity_ai);
        BottomNavHelper.bind(this, R.id.nav_ai);

        messages = findViewById(R.id.aiMessages);
        scroll = findViewById(R.id.aiScroll);
        input = findViewById(R.id.aiInput);
        send = findViewById(R.id.aiSend);
        targetView = findViewById(R.id.aiTarget);

        //While the keyboard is open the bottom navigation would only take room from the chat
        View root = findViewById(R.id.aiRoot);
        View nav = findViewById(R.id.mainBottomNav);
        View inputBar = findViewById(R.id.aiInputBar);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            boolean keyboard = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0;
            nav.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            //A gone view is no usable anchor in a RelativeLayout, so the input bar sits on the bottom edge instead
            RelativeLayout.LayoutParams bar = (RelativeLayout.LayoutParams) inputBar.getLayoutParams();
            if (keyboard) {
                bar.removeRule(RelativeLayout.ABOVE);
                bar.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            } else {
                bar.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                bar.addRule(RelativeLayout.ABOVE, R.id.mainBottomNav);
            }
            inputBar.setLayoutParams(bar);
            v.setPadding(0, 0, 0, keyboard ? ime.bottom : 0);
            return windowInsets;
        });

        speech = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getData() == null)
                return;
            ArrayList<String> heard = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty()) {
                String current = input.getText().toString();
                input.setText(current.isEmpty() ? heard.get(0) : current + " " + heard.get(0));
                input.setSelection(input.getText().length());
            }
        });

        micPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted)
                startRecording();
            else
                Toast.makeText(this, R.string.phyerma_ai_mic_denied, Toast.LENGTH_LONG).show();
        });
        mic = findViewById(R.id.aiMic);
        inputHint = input.getHint();
        mic.setOnClickListener(v -> listen());
        mic.setOnLongClickListener(v -> {
            editSpeechKey();
            return true;
        });
        send.setOnClickListener(v -> submit());
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        targetView.setOnClickListener(v -> chooseTarget());
        findViewById(R.id.aiKey).setOnClickListener(v -> editKey());
    }

    @Override
    public void finish() {
        super.finish();
        BottomNavHelper.fade(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.setListener(this);
        render();
        if (!AiKeyStore.has(this) && controller.entries().isEmpty())
            editKey();
    }

    @Override
    protected void onPause() {
        if (recorder.recording())
            recorder.stop();
        controller.setListener(null);
        super.onPause();
    }

    @Override
    public void onChanged() {
        render();
    }

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || controller.busy())
            return;
        if (!AiKeyStore.has(this)) {
            editKey();
            return;
        }
        input.setText("");
        controller.send(this, text);
    }

    //A speech key means our own recording; without one the system recognizer is tried, and a phone
    //that has none is offered the key dialog.
    private void listen() {
        if (recorder.recording()) {
            recorder.stop();
            return;
        }
        if (transcribing)
            return;
        if (AiKeyStore.hasSpeech(this)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                startRecording();
            else
                micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.phyerma_ai_speak_prompt));
        try {
            speech.launch(intent);
        } catch (ActivityNotFoundException e) {
            editSpeechKey();
        }
    }

    private void startRecording() {
        mic.setColorFilter(Color.RED);
        input.setHint(R.string.phyerma_ai_recording);
        recorder.start(pcm -> runOnUiThread(() -> recorded(pcm)));
    }

    private void recorded(short[] pcm) {
        mic.clearColorFilter();
        input.setHint(inputHint);
        if (pcm == null) {
            Toast.makeText(this, R.string.phyerma_ai_mic_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (isFinishing() || pcm.length < SpeechClient.SAMPLE_RATE / 4)
            return;
        transcribing = true;
        input.setHint(R.string.phyerma_ai_transcribing);
        byte[] wav = SpeechClient.wav(pcm, pcm.length, SpeechClient.SAMPLE_RATE);
        String key = AiKeyStore.getSpeech(this);
        new Thread(() -> {
            String text = null;
            String error = null;
            try {
                text = SpeechClient.transcribe(key, wav);
            } catch (DeepSeekClient.ApiException e) {
                error = e.status == 401 ? getString(R.string.phyerma_ai_speech_key_bad) : e.getMessage();
            } catch (IOException e) {
                error = getString(R.string.phyerma_ai_speech_network);
            }
            String heard = text;
            String problem = error;
            runOnUiThread(() -> {
                transcribing = false;
                input.setHint(inputHint);
                if (problem != null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                } else if (heard == null || heard.isEmpty()) {
                    Toast.makeText(this, R.string.phyerma_ai_speech_empty, Toast.LENGTH_LONG).show();
                } else {
                    String current = input.getText().toString();
                    input.setText(current.isEmpty() ? heard : current + " " + heard);
                    input.setSelection(input.getText().length());
                }
            });
        }, "speech-to-text").start();
    }

    private void render() {
        List<AiController.Entry> entries = controller.entries();
        messages.removeAllViews();
        if (entries.isEmpty())
            messages.addView(bubble(getString(R.string.phyerma_ai_welcome), false));
        for (AiController.Entry entry : entries) {
            switch (entry.kind) {
                case USER:
                    messages.addView(bubble(entry.text, true));
                    break;
                case BOT:
                    messages.addView(bubble(entry.text, false));
                    break;
                case NOTE:
                    messages.addView(note(entry.resolve(this), false));
                    break;
                case ERROR:
                    messages.addView(note(entry.resolve(this), true));
                    break;
                case RESULT:
                    messages.addView(result(entry));
                    break;
            }
        }
        if (controller.busy())
            messages.addView(note(getString(R.string.phyerma_ai_working), false));

        boolean busy = controller.busy();
        send.setEnabled(!busy);
        targetView.setEnabled(!busy);
        showTarget();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showTarget() {
        ExperimentStore.Ref ref = controller.target();
        targetView.setText(ref == null
                ? getString(R.string.phyerma_ai_target_new)
                : getString(R.string.phyerma_ai_target_label, ref.title));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView bubble(String text, boolean mine) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15);
        view.setTextIsSelectable(true);
        view.setBackgroundResource(mine ? R.drawable.bg_ai_user : R.drawable.bg_ai_bot);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = mine ? Gravity.END : Gravity.START;
        params.topMargin = dp(6);
        if (mine)
            params.setMarginStart(dp(48));
        else
            params.setMarginEnd(dp(48));
        view.setLayoutParams(params);
        return view;
    }

    private TextView note(String text, boolean error) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(error ? 0xffff8a80 : getColor(R.color.phyphox_white_70));
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(dp(8), dp(6), dp(8), dp(2));
        return view;
    }

    //The card for a written experiment, with the actions that make sense for it
    private View result(AiController.Entry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_ai_bot);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(entry.undone ? getString(R.string.phyerma_ai_undone) : entry.resolve(this));
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        if (!entry.undone) {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button open = new Button(this);
            open.setText(R.string.phyerma_ai_open);
            open.setOnClickListener(v -> openExperiment(entry.file));
            Button undo = new Button(this);
            undo.setText(R.string.phyerma_ai_undo);
            undo.setOnClickListener(v -> controller.undo(entry));
            buttons.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (controller.canUndo(entry)) {
                LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                second.setMarginStart(dp(8));
                buttons.addView(undo, second);
            }
            card.addView(buttons);
        }
        return card;
    }

    private void openExperiment(String file) {
        Intent intent = new Intent(this, Experiment.class);
        intent.putExtra(Const.EXPERIMENT_XML, file);
        intent.putExtra(Const.EXPERIMENT_ISTEMP, (String) null);
        intent.putExtra(Const.EXPERIMENT_ISASSET, false);
        intent.putExtra(Const.EXPERIMENT_UNAVAILABLESENSOR, 0);
        intent.setAction(Intent.ACTION_VIEW);
        startActivity(intent);
    }

    private void chooseTarget() {
        //Reading ~170 titles takes a moment, so keep it off the main thread
        new Thread(() -> {
            List<ExperimentStore.Ref> refs = ExperimentStore.list(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                String[] labels = new String[refs.size() + 1];
                labels[0] = getString(R.string.phyerma_ai_target_new);
                for (int i = 0; i < refs.size(); i++) {
                    ExperimentStore.Ref ref = refs.get(i);
                    labels[i + 1] = ref.asset ? getString(R.string.phyerma_ai_target_builtin, ref.title) : ref.title;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.phyerma_ai_target_title)
                        .setItems(labels, (dialog, which) -> controller.selectTarget(this, which == 0 ? null : refs.get(which - 1)))
                        .show();
            });
        }).start();
    }

    private void editKey() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("sk-…");
        field.setText(AiKeyStore.get(this));
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        holder.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.phyerma_ai_key_title)
                .setMessage(R.string.phyerma_ai_key_message)
                .setView(holder)
                .setPositiveButton(R.string.phyerma_ai_key_save, (d, w) -> AiKeyStore.set(this, field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null);
        if (AiKeyStore.has(this))
            builder.setNeutralButton(R.string.phyerma_ai_key_remove, (d, w) -> AiKeyStore.clear(this));
        builder.show();
    }

    private void editSpeechKey() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("sk-…");
        field.setText(AiKeyStore.getSpeech(this));
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        holder.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.phyerma_ai_speech_key_title)
                .setMessage(R.string.phyerma_ai_speech_key_message)
                .setView(holder)
                .setPositiveButton(R.string.phyerma_ai_key_save, (d, w) -> AiKeyStore.setSpeech(this, field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null);
        if (AiKeyStore.hasSpeech(this))
            builder.setNeutralButton(R.string.phyerma_ai_key_remove, (d, w) -> AiKeyStore.clearSpeech(this));
        builder.show();
    }
}

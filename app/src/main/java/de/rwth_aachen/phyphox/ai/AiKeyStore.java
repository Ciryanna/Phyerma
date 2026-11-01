package de.rwth_aachen.phyphox.ai;

import android.content.Context;
import android.content.SharedPreferences;

//The user's own API keys: DeepSeek's for the chat, and a speech-to-text one for phones without a system
//recognizer. Kept in a private preferences file that the backup rules exclude (res/xml/backup_rules.xml);
//each is only ever sent to its own service (api.deepseek.com, api.siliconflow.cn).
public final class AiKeyStore {
    static final String FILE = "phyerma_ai";
    private static final String KEY = "deepseek_api_key";
    private static final String SPEECH_KEY = "speech_api_key";

    private AiKeyStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String get(Context context) {
        return prefs(context).getString(KEY, "");
    }

    public static boolean has(Context context) {
        return !get(context).isEmpty();
    }

    public static void set(Context context, String key) {
        prefs(context).edit().putString(KEY, key.trim()).apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    public static String getSpeech(Context context) {
        return prefs(context).getString(SPEECH_KEY, "");
    }

    public static boolean hasSpeech(Context context) {
        return !getSpeech(context).isEmpty();
    }

    public static void setSpeech(Context context, String key) {
        prefs(context).edit().putString(SPEECH_KEY, key.trim()).apply();
    }

    public static void clearSpeech(Context context) {
        prefs(context).edit().remove(SPEECH_KEY).apply();
    }
}

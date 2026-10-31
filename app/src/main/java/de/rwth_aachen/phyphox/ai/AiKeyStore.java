package de.rwth_aachen.phyphox.ai;

import android.content.Context;
import android.content.SharedPreferences;

//The user's own DeepSeek API key. Kept in a private preferences file that the backup rules exclude
//(res/xml/backup_rules.xml); it is only ever sent to api.deepseek.com.
public final class AiKeyStore {
    static final String FILE = "phyerma_ai";
    private static final String KEY = "deepseek_api_key";

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
}

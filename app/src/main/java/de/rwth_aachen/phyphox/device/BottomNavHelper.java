package de.rwth_aachen.phyphox.device;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import de.rwth_aachen.phyphox.ExperimentList.ExperimentListActivity;
import de.rwth_aachen.phyphox.R;
import de.rwth_aachen.phyphox.ai.AiActivity;
import de.rwth_aachen.phyphox.helper.WindowInsetHelper;

public final class BottomNavHelper {
    private BottomNavHelper() {}

    //The three tabs are separate activities that look alike (same header, same bar), so the system's
    //slide between them reads as a jump; a short cross-fade keeps the shared parts still.
    @SuppressWarnings("deprecation")
    public static void fade(Activity activity) {
        activity.overridePendingTransition(R.anim.tab_fade_in, R.anim.tab_fade_out);
    }

    public static void bind(Activity activity, int selectedItemId) {
        BottomNavigationView nav = activity.findViewById(R.id.mainBottomNav);
        if (nav == null)
            return;
        WindowInsetHelper.setInsets(nav, WindowInsetHelper.ApplyTo.PADDING, WindowInsetHelper.ApplyTo.IGNORE, WindowInsetHelper.ApplyTo.PADDING, WindowInsetHelper.ApplyTo.PADDING);
        View header = activity.findViewById(R.id.pageHeader);
        if (header != null)
            WindowInsetHelper.setInsets(header, WindowInsetHelper.ApplyTo.PADDING, WindowInsetHelper.ApplyTo.PADDING, WindowInsetHelper.ApplyTo.PADDING, WindowInsetHelper.ApplyTo.IGNORE);
        TextView subtitle = activity.findViewById(R.id.headerSubtitle);
        if (subtitle != null) {
            if (selectedItemId == R.id.nav_device)
                subtitle.setText(R.string.phyerma_nav_device);
            else if (selectedItemId == R.id.nav_ai)
                subtitle.setText(R.string.phyerma_nav_ai);
        }
        LanguageHelper.bind(activity);
        nav.setSelectedItemId(selectedItemId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedItemId)
                return true;
            if (id == R.id.nav_experiments) {
                Intent intent = new Intent(activity, ExperimentListActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
                fade(activity);
                if (!(activity instanceof ExperimentListActivity))
                    activity.finish();
                return true;
            }
            if (id == R.id.nav_device) {
                activity.startActivity(new Intent(activity, CalibrationActivity.class));
                fade(activity);
                if (!(activity instanceof ExperimentListActivity))
                    activity.finish();
                return true;
            }
            if (id == R.id.nav_ai) {
                activity.startActivity(new Intent(activity, AiActivity.class));
                fade(activity);
                if (!(activity instanceof ExperimentListActivity))
                    activity.finish();
                return true;
            }
            return false;
        });
    }
}

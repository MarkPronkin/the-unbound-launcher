package dev.beampro.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;

/** Forward setup payloads without repeating the home screen's task-launch operation. */
public final class SetupNavigation {
    private SetupNavigation() {}

    public static Intent forward(Activity source, Class<?> target) {
        return forward(source, target.getName());
    }

    public static Intent forward(Activity source, String target) {
        Intent next=new Intent(source.getIntent());
        // NEW_TASK/RESET_TASK_IF_NEEDED with MAIN/LAUNCHER can deliver the
        // existing task instead of creating the explicit next setup Activity.
        // Keep only URI access grants; the caller Activity owns navigation.
        int grants=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        next.setFlags(next.getFlags()&grants);
        next.removeCategory(Intent.CATEGORY_LAUNCHER);
        next.removeCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (Intent.ACTION_MAIN.equals(next.getAction())) next.setAction(null);
        next.setSelector(null);
        return next.setComponent(new ComponentName(source, target));
    }

    public static Intent launcher(Activity source) {
        String target = source.getPackageName().equals("dev.beampro.settings.preview")
                ? "dev.beampro.settings.preview.SettingsPreviewActivity" : "ai.nreal.nebula.activity.splash.SplashActivity";
        Intent intent = forward(source, target);
        // A setup return is a normal phone launch. Preserve actual USB actions and
        // payloads, but restore MAIN after stripping it from the intermediate steps.
        if (intent.getAction() == null) intent.setAction(Intent.ACTION_MAIN);
        intent.removeExtra("dev.beampro.adb.SETUP");
        return intent;
    }
}

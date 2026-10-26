package dev.beampro.bridge;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Restore launcher semantics for the stock spatial launcher's empty VIEW intents. */
final class SpatialLaunchIntent {
    static Intent prepare(Intent target, PackageManager packages) {
        Intent launch = new Intent(target);
        ComponentName component = target.getComponent();
        Bundle extras = target.getExtras();
        if (component == null || !Intent.ACTION_VIEW.equals(target.getAction())
                || target.getData() != null || target.getType() != null
                || target.getClipData() != null || target.getSelector() != null
                || (extras != null && !extras.isEmpty())
                || (target.getCategories() != null && !target.getCategories().isEmpty())) {
            return launch;
        }
        Intent launcher = packages.getLaunchIntentForPackage(component.getPackageName());
        if (launcher == null || !component.equals(launcher.getComponent())) return launch;

        // Chrome routes an empty VIEW to an existing phone task, even when its
        // initial activity was given a launch display and MULTIPLE_TASK. Use the
        // installed app's real launcher action/categories for an app-icon open.
        // Explicit pages, settings actions and payload-bearing Intents stay intact.
        launch.setAction(launcher.getAction());
        if (launcher.getCategories() != null) {
            for (String category : launcher.getCategories()) launch.addCategory(category);
        }
        return launch;
    }
}

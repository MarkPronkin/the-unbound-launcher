package dev.beampro.adb;

import android.content.Context;

/** The same pinned payload and shell identity are used by both connection methods. */
final class HelperStarter {
    static String command(Context context) {
        String dir = "/data/local/tmp/" + context.getPackageName();
        String jar = dir + "/helper-" + HelperPayload.SHA256 + ".jar";
        return "mkdir -p " + dir + " && chmod 700 " + dir
                + " && rm -f " + jar + ".new"
                + " && unzip -p " + quote(context.getApplicationInfo().sourceDir) + " assets/unbound-shell.jar > " + jar + ".new"
                + " && [ \"$(sha256sum " + jar + ".new | cut -d' ' -f1)\" = " + quote(HelperPayload.SHA256) + " ]"
                + " && chmod 444 " + jar + ".new && mv -f " + jar + ".new " + jar
                + " && (CLASSPATH=" + jar + " /system/bin/nohup /system/bin/setsid /system/bin/app_process / dev.beampro.bridge.LaunchServer --embedded"
                + " > " + dir + "/helper.log 2>&1 < /dev/null & sleep 0.3) && echo UNBOUND_HELPER_STARTED";
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private HelperStarter() {}
}

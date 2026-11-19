package dev.beampro.port;

import android.content.Context;
import android.media.MediaActionSound;

/** Unity's NativeMediaActionSound constructs this class and calls play(int). */
public final class CaptureSounds {
    private static volatile Context application;
    private final MediaActionSound player;

    static void install(Context context) {
        application = context.getApplicationContext();
    }

    public CaptureSounds() {
        this(new MediaActionSound());
    }

    CaptureSounds(MediaActionSound player) {
        this.player = player;
    }

    public void play(int sound) {
        if (sound == MediaActionSound.START_VIDEO_RECORDING
                && ExperimentalOptionsCompat.quietCaptureSounds(application)) return;
        player.play(sound);
    }
}

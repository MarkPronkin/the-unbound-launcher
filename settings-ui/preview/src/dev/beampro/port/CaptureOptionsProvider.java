package dev.beampro.port;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;

/** Exercises the running capture client from a separate Android process. */
public final class CaptureOptionsProvider extends ContentProvider {
    private static final class PlaybackProbe extends MediaActionSound {
        int lastSound;
        @Override public void play(int sound) { lastSound = sound; }
    }

    private PlaybackProbe probe;
    private CaptureSounds sounds;

    @Override public boolean onCreate() {
        CaptureSounds.install(getContext());
        probe = new PlaybackProbe();
        sounds = new CaptureSounds(probe);
        return true;
    }

    private int playedSound(int sound) {
        probe.lastSound = -1;
        sounds.play(sound);
        return probe.lastSound;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        int shutter = context.getResources().getIdentifier("kaca", "raw", context.getPackageName());
        Bundle state = new Bundle();
        state.putInt("pid", android.os.Process.myPid());
        state.putBoolean("quiet", ExperimentalOptionsCompat.suppressPhotoSound(context, shutter));
        state.putBoolean("skip", ExperimentalOptionsCompat.skipScreenOffConfirmation(context));
        state.putInt("startCue", playedSound(MediaActionSound.START_VIDEO_RECORDING));
        state.putInt("stopCue", playedSound(MediaActionSound.STOP_VIDEO_RECORDING));
        return state;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}

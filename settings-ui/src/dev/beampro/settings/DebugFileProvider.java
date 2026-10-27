package dev.beampro.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Only completed debug ZIPs are shareable, through temporary read grants. */
public final class DebugFileProvider extends ContentProvider {
    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".unbound.debug")
                .appendPath(file.getName()).build();
    }
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !DebugArchive.validName(name)) throw new FileNotFoundException("Unknown debug file");
        File directory = new File(getContext().getFilesDir(), "debug/exports");
        File file = new File(directory, name);
        try {
            if (!uri(getContext(), file).equals(uri) || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
                throw new IOException("Unknown debug file");
            DebugData.validate(file);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Debug files are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values); return cursor;
        } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}

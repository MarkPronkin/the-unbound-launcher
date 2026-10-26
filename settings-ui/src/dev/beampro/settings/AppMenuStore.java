package dev.beampro.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The original Unity layout is authoritative, including changes made in glasses. */
public final class AppMenuStore {
    public static final int HIDDEN = Integer.MAX_VALUE;
    static final String LAYOUT_PATH = "AppDetails/EPHInfoConfig/LayoutConfig.json";
    private static final String LEGACY_PATH = "EPHInfoConfig/LayoutConfig.json";
    private final AtomicFile file;
    private final SharedPreferences preferences;
    private final Map<String, Integer> hiddenOrder = new LinkedHashMap<>();
    private byte[] loaded;
    private final Map<String, Integer> positions = new LinkedHashMap<>();

    public AppMenuStore(Context context) throws IOException {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IOException("App storage is unavailable. Try again after unlocking your phone.");
        file = new AtomicFile(new File(root, LAYOUT_PATH));
        preferences = context.getSharedPreferences("beampro_app_menu", Context.MODE_PRIVATE);
        migrateLegacy(new AtomicFile(new File(root, LEGACY_PATH)));
        loaded = read();
        positions.putAll(decode(loaded));
        try {
            JSONArray saved = new JSONArray(preferences.getString("hidden_order", "[]"));
            for (int i = 0; i < saved.length(); i++) hiddenOrder.put(saved.getString(i), i);
        } catch (Exception ignored) { /* Optional ordering metadata never changes native visibility. */ }
    }

    private static Map<String, Integer> decode(byte[] content) throws IOException {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (content.length == 0) return result;
        try {
            JSONObject json = new JSONObject(new String(content, StandardCharsets.UTF_8));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                if (!(value instanceof Number) || ((Number)value).doubleValue() != ((Number)value).intValue()) {
                    throw new IllegalArgumentException("Invalid position for " + key);
                }
                result.put(key, ((Number)value).intValue());
            }
        } catch (Exception error) {
            throw new IOException("The saved app menu could not be read. Its contents have been kept.", error);
        }
        return result;
    }

    private byte[] read() throws IOException { return read(file); }
    private static byte[] read(AtomicFile file) throws IOException {
        // AtomicFile also recovers an interrupted write before returning its contents.
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return new byte[0];
        return file.readFully();
    }

    /** Recover choices saved by the first phone editor, even when an older AR layout exists. */
    private void migrateLegacy(AtomicFile legacy) throws IOException {
        byte[] chosen = read(legacy);
        if (chosen.length == 0) return;
        decode(chosen); // Preserve malformed files without replacing the launcher's working layout.
        byte[] previous = read();
        AtomicFile backup = new AtomicFile(new File(file.getBaseFile() + ".before-phone-migration"));
        if (previous.length > 0 && !Arrays.equals(previous, chosen) && read(backup).length == 0) write(backup, previous);
        write(file, chosen);
        // Archive rather than leave a second active source that could overwrite future AR edits.
        File archived = new File(legacy.getBaseFile() + ".migrated");
        if (!legacy.getBaseFile().renameTo(archived)) throw new IOException("Menu recovered, but its previous file could not be archived. Reopen App menu to retry.");
    }

    private static void write(AtomicFile file, byte[] content) throws IOException {
        File parent = file.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create app-menu storage");
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(content); file.finishWrite(stream);
        } catch (IOException error) { file.failWrite(stream); throw error; }
        if (!Arrays.equals(content, read(file))) throw new IOException("The saved app menu could not be verified. Reopen App menu to check its contents.");
    }

    public boolean isHidden(String id) { return positions.containsKey(id) && positions.get(id) == HIDDEN; }
    byte[] snapshot() { return loaded.clone(); }
    void expectSnapshot(byte[] previous) { if (previous != null) loaded = previous.clone(); }

    public List<String> order(List<String> defaults) {
        List<String> result = new ArrayList<>(defaults);
        // Stable sorting preserves the launcher's default order for newly installed apps.
        Collections.sort(result, Comparator.comparingInt((String id) -> {
            Integer value = positions.get(id);
            return value != null && value >= 0 ? value : HIDDEN - 1;
        }).thenComparingInt(id -> isHidden(id) && hiddenOrder.containsKey(id) ? hiddenOrder.get(id) : HIDDEN));
        return result;
    }

    public void save(List<String> visible, List<String> hidden) throws IOException {
        save(visible, hidden, null);
    }

    public void save(List<String> visible, List<String> hidden, Boolean hideOtherEntries) throws IOException {
        if (!Arrays.equals(loaded, read())) {
            throw new IOException("The menu changed since you opened this screen. Reopen App menu to load the latest order.");
        }
        Map<String, Integer> next = new LinkedHashMap<>();
        for (String id : visible) {
            if (next.put(id, next.size()) != null) throw new IOException("Duplicate app in menu");
        }
        for (String id : hidden) {
            if (next.put(id, HIDDEN) != null) throw new IOException("App cannot be both visible and hidden");
        }
        // Keep unavailable/custom entries and future app types. Give visible entries
        // distinct trailing positions so returning packages cannot overwrite other slots.
        for (Map.Entry<String, Integer> entry : positions.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                boolean hide = hideOtherEntries != null ? hideOtherEntries : entry.getValue() == HIDDEN;
                next.put(entry.getKey(), hide ? HIDDEN : next.size());
            }
        }
        byte[] content = (new JSONObject(next).toString() + "\n").getBytes(StandardCharsets.UTF_8);
        write(file, content);
        loaded = content;
        positions.clear(); positions.putAll(next);
        // All hidden entries share the native INT_MAX sentinel. Remember their order
        // on the phone so Hide all → Save → reopen → Show all preserves the layout.
        hiddenOrder.clear(); JSONArray order = new JSONArray();
        for (Map.Entry<String, Integer> entry : next.entrySet()) if (entry.getValue() == HIDDEN) {
            hiddenOrder.put(entry.getKey(), hiddenOrder.size()); order.put(entry.getKey());
        }
        if (!preferences.edit().putString("hidden_order", order.toString()).commit()) {
            throw new IOException("App visibility was saved, but the hidden app order could not be remembered. Try Save again.");
        }
    }
}

package dev.beampro.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Bounded launcher-only history. No Android task IDs or launch Intent data are saved. */
final class RecentAppsStore {
    static final int LIMIT = 32;
    private static final int MAGIC = 0x42505231;
    private final File file;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private int nextId = -1;

    static final class Entry {
        final int id;
        final String packageName;
        final long lastOpened;

        Entry(int id, String packageName, long lastOpened) {
            this.id = id;
            this.packageName = packageName;
            this.lastOpened = lastOpened;
        }
    }

    RecentAppsStore(File file) {
        this.file = file;
        if (!file.isFile()) return;
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("Unknown recent-apps format");
            int storedNextId = input.readInt();
            int count = input.readInt();
            if (storedNextId >= 0 || count < 0 || count > LIMIT) throw new IOException("Invalid recent-apps header");
            for (int i = 0; i < count; i++) {
                int id = input.readInt();
                String name = input.readUTF();
                long time = input.readLong();
                if (id >= 0 || id <= storedNextId || !validPackage(name)) throw new IOException("Invalid recent app");
                for (Entry entry : entries) {
                    if (entry.id == id || entry.packageName.equals(name)) throw new IOException("Duplicate recent app");
                }
                entries.add(new Entry(id, name, time));
            }
            if (input.read() != -1) throw new IOException("Trailing recent-apps data");
            nextId = storedNextId;
        } catch (IOException error) {
            entries.clear();
            System.err.println("BEAM_RECENTS_LOAD_FAILED " + error);
        }
    }

    private static boolean validPackage(String name) {
        return name != null && name.length() <= 255 && name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    synchronized void record(String packageName, long now) throws IOException {
        if (!validPackage(packageName)) throw new IllegalArgumentException("Invalid package name");
        Entry previous = null;
        for (Entry entry : entries) if (entry.packageName.equals(packageName)) previous = entry;
        int id;
        if (previous == null) {
            if (nextId == Integer.MIN_VALUE) throw new IOException("Recent-app IDs exhausted");
            id = nextId--;
        } else {
            id = previous.id;
            entries.remove(previous);
        }
        entries.add(0, new Entry(id, packageName, now));
        while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
        save();
    }

    synchronized List<Entry> list() { return new ArrayList<>(entries); }

    synchronized void remove(int id) throws IOException {
        if (entries.removeIf(entry -> entry.id == id)) save();
    }

    private void save() throws IOException {
        File pending = new File(file.getPath() + ".new");
        try {
            try (FileOutputStream stream = new FileOutputStream(pending);
                 DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(MAGIC);
                output.writeInt(nextId);
                output.writeInt(entries.size());
                for (Entry entry : entries) {
                    output.writeInt(entry.id);
                    output.writeUTF(entry.packageName);
                    output.writeLong(entry.lastOpened);
                }
                output.flush();
                stream.getFD().sync();
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
}

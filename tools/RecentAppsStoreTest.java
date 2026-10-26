package dev.beampro.bridge;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public final class RecentAppsStoreTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File file = new File(args[1], "recents.bin");
        RecentAppsStore store = new RecentAppsStore(file);
        check(store.list().isEmpty(), "New history must be empty");
        switch (args[0]) {
            case "order": {
                store.record("test.app.one", 1);
                int firstId = store.list().get(0).id;
                store.record("test.app.two", 2);
                store.record("test.app.one", 3);
                List<RecentAppsStore.Entry> entries = store.list();
                check(entries.size() == 2, "Reopening must not duplicate an app");
                check(entries.get(0).packageName.equals("test.app.one"), "Newest must be first");
                check(entries.get(0).id == firstId && entries.get(0).lastOpened == 3, "Stable ID, refreshed time");
                entries.clear();
                check(store.list().size() == 2, "Caller cannot mutate the history");
                RecentAppsStore reloaded = new RecentAppsStore(file);
                check(reloaded.list().size() == 2 && reloaded.list().get(0).id == firstId, "History must survive restart");
                reloaded.record("test.app.three", 4);
                check(reloaded.list().get(0).id < reloaded.list().get(1).id, "IDs remain unique after restart");
                break;
            }
            case "limit": {
                for (int i = 0; i < 100; i++) store.record("test.app.n" + i, i);
                check(store.list().size() == RecentAppsStore.LIMIT, "Bounded list");
                check(store.list().get(0).packageName.equals("test.app.n99"), "Newest survives trimming");
                check(store.list().get(31).packageName.equals("test.app.n68"), "Oldest entries evicted");
                check(new RecentAppsStore(file).list().size() == 32, "Bound survives persistence");
                break;
            }
            case "dismiss": {
                store.record("test.app.one", 1);
                store.record("test.app.two", 2);
                int id = store.list().get(1).id;
                store.remove(id);
                store.remove(123); // Synthetic IDs cannot remove unrelated Android tasks.
                check(new RecentAppsStore(file).list().size() == 1, "Dismissal survives restart");
                store.record("test.app.one", 3);
                check(store.list().size() == 2 && store.list().get(0).id != id, "Dismissed app can be reopened");
                break;
            }
            case "corrupt": {
                store.record("test.app.one", 1);
                byte[] valid = Files.readAllBytes(file.toPath());
                Files.write(file.toPath(), java.util.Arrays.copyOf(valid, valid.length - 2));
                store = new RecentAppsStore(file);
                check(store.list().isEmpty(), "Truncated history must not break helper startup");
                store.record("test.app.two", 2);
                check(new RecentAppsStore(file).list().size() == 1, "History recovers after a corrupt file");
                break;
            }
            case "invalid": {
                for (String name : new String[]{"", "test/invalid", "test.app\nother", "justone"}) {
                    try { store.record(name, 1); throw new AssertionError("Invalid package accepted"); }
                    catch (IllegalArgumentException expected) { }
                }
                check(store.list().isEmpty() && !file.exists(), "Bad records leave no history");
                break;
            }
            default: throw new IllegalArgumentException(args[0]);
        }
        check(!new File(file.getPath() + ".new").exists(), "No pending write left behind");
    }
}

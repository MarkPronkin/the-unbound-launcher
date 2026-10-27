package dev.beampro.bridge;

import java.io.*;

/** Exercise installer restoration, readback and caller boundaries against the production policy. */
public final class AppClassificationTest {
    static int assertions;
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); assertions++; }
    static final class Fake implements AppClassification.Platform {
        int category = -1, uid = 12345, writes;
        String installer = "system.installer";
        boolean signed = true, reject, ignore, failRestore;
        public int uid() { return uid; }
        public int category() { return category; }
        public String installer() { return installer; }
        public boolean platformInstaller(String name) { return signed; }
        public void installer(String name) throws IOException {
            if (failRestore && !"com.android.shell".equals(name)) throw new IOException("restore failed");
            installer = name;
        }
        public void category(int value) throws IOException {
            check("com.android.shell".equals(installer), "category call uses real installer identity");
            writes++;
            if (reject) throw new IOException("denied");
            if (!ignore) category = value;
        }
    }
    static void fails(AppClassification policy, int caller, int operation) throws Exception {
        try { policy.request(caller, operation); throw new AssertionError("request accepted"); }
        catch (SecurityException | IllegalArgumentException | IOException | IllegalStateException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        File journal = new File(args[0], "category-journal");
        Fake platform = new Fake();
        AppClassification policy = new AppClassification(platform, 12345, journal);
        check(policy.request(12345, -1) == -1 && platform.writes == 0, "query is read only");
        fails(policy, 23456, 1); fails(policy, 2000, 1); fails(policy, 12345, 2);
        platform.uid = 23456; fails(policy, 12345, 1); platform.uid = 12345;
        check(platform.writes == 0 && !journal.exists(), "rejections have no side effects");
        check(policy.request(12345, 1) == 0, "game category applied");
        check(platform.installer.equals("system.installer") && !journal.exists(), "installer restored after success");
        check(policy.request(12345, 1) == 0 && platform.writes == 1, "already applied change is idempotent");
        check(policy.request(12345, 0) == -1, "normal category restored");
        platform.reject = true; fails(policy, 12345, 1);
        check(platform.category == -1 && platform.installer.equals("system.installer") && !journal.exists(), "failed change restores installer");
        platform.reject = false; platform.ignore = true; fails(policy, 12345, 1);
        check(platform.installer.equals("system.installer"), "ignored setter is detected after restoration");
        platform.ignore = false; platform.signed = false; fails(policy, 12345, 1);
        check(platform.installer.equals("system.installer"), "foreign installer never replaced");
        platform.installer = null;
        check(policy.request(12345, 1) == 0 && platform.installer == null, "absent installer restored to null");
        platform.installer = "com.android.shell";
        check(policy.request(12345, 0) == -1 && !journal.exists(), "ADB install needs no installer change");
        platform.installer = "system.installer"; platform.signed = true; platform.failRestore = true;
        fails(policy, 12345, 1);
        check(journal.exists() && platform.installer.equals("com.android.shell"), "failed restoration retains recovery journal");
        platform.failRestore = false;
        policy = new AppClassification(platform, 12345, journal);
        check(platform.installer.equals("system.installer") && !journal.exists(), "helper restart recovers installer");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(journal))) { out.writeBoolean(true); out.writeUTF("old.installer"); }
        platform.installer = "new.installer";
        policy = new AppClassification(platform, 12345, journal);
        check(platform.installer.equals("new.installer") && !journal.exists(), "recovery preserves subsequent update installer");
        System.out.println("PASS app classification assertions: " + assertions);
    }
}

package dev.beampro.bridge;

/** Host regression for the same command runner packaged in the Android helper. */
public final class ShellCommandTest {
    private static ShellCommand.Result run(long timeout, String mode) throws Exception {
        return ShellCommand.run(timeout, System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), ShellCommandTest.class.getName(), "child", mode);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            switch (args[1]) {
                case "success": System.out.println("Starting: Intent { example }"); break;
                case "rejected": System.out.println("Error: Activity not started"); break;
                case "exception": System.err.println("java.lang.SecurityException: denied"); break;
                case "exit": System.exit(3); break;
                case "slow": System.out.println("Starting"); Thread.sleep(10000); break;
                case "noisy":
                    for (int i = 0; i < 131072; i++) System.out.print("data");
                    break;
                default: throw new AssertionError(args[1]);
            }
            return;
        }
        switch (args[0]) {
            case "success": check(run(3000, "success").succeeded(), "Normal launch rejected"); break;
            case "rejections":
                for (String mode : new String[]{"rejected", "exception", "exit"}) {
                    check(!run(3000, mode).succeeded(), "Failure accepted: " + mode);
                }
                check(run(3000, "success").succeeded(), "Launch after failure rejected");
                break;
            case "timeout":
                long started = System.nanoTime();
                ShellCommand.Result slow = run(300, "slow");
                check(slow.timedOut && !slow.succeeded(), "Slow command did not time out");
                check((System.nanoTime() - started) / 1000000 < 3000, "Timeout did not bound waiting");
                check(run(3000, "success").succeeded(), "Runner unusable after timeout");
                break;
            case "noisy":
                ShellCommand.Result noisy = run(5000, "noisy");
                check(noisy.succeeded(), "Full output pipe blocked command");
                check(noisy.output.length() == 65536, "Output not bounded");
                break;
            case "missing":
                try {
                    ShellCommand.run(1000, "/beampro-nonexistent-command");
                    throw new AssertionError("Missing command accepted");
                } catch (java.io.IOException expected) { }
                check(run(3000, "success").succeeded(), "Runner unusable after exec failure");
                break;
            default: throw new AssertionError(args[0]);
        }
    }
}

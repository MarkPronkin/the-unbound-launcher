import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decompile selected classes in one JADX session, retaining smali for exact checks. */
public class ExtractClasses {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: ExtractClasses INPUT OUTPUT_DIR CLASS...");
        }
        JadxArgs options = new JadxArgs();
        options.setInputFile(new File(args[0]));
        options.setSkipResources(true);
        options.setThreadsCount(4);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        try (JadxDecompiler decompiler = new JadxDecompiler(options)) {
            decompiler.load();
            for (int i = 2; i < args.length; i++) {
                JavaClass cls = decompiler.searchJavaClassByOrigFullName(args[i]);
                if (cls == null) {
                    throw new IllegalArgumentException("Missing class: " + args[i]);
                }
                Path base = output.resolve(args[i].replace('.', '/'));
                Files.createDirectories(base.getParent());
                Files.writeString(Path.of(base + ".java"), cls.getCode());
                Files.writeString(Path.of(base + ".smali"), cls.getSmali());
                System.out.println(args[i]);
            }
        }
    }
}

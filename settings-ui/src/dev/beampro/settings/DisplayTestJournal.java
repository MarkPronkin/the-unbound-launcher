package dev.beampro.settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Fixed local test history and three labelled captures, independent of logcat collection. */
final class DisplayTestJournal {
    static final int MAX_REPORT_BYTES=64_000, CAPTURES=3;
    static final int EXPORT_RESERVE=MAX_REPORT_BYTES+CAPTURES*(DisplayDiagnostics.MAX_STARTUP_BYTES+256)+4096;
    private final File directory;
    DisplayTestJournal(File directory) { this.directory=directory; }
    private File file(String name) { return new File(directory,"display-test-"+name+".txt"); }
    synchronized boolean hasData() { return file("history").isFile(); }
    synchronized void begin(String run, String label) throws IOException {
        ensure();write(file("run"),run.getBytes(StandardCharsets.UTF_8));
        for(String stage:new String[]{"before","stereo","restored"})if(file(stage).exists()&&!file(stage).delete())throw new IOException("Cannot reset a display test capture");
        note(run,"BEGIN "+label+"; only display/control diagnostics are recorded. No screenshot or head pose is captured.");
    }
    synchronized void note(String run, String text) throws IOException {
        ensure();
        String old=file("history").isFile()?new String(Files.readAllBytes(file("history").toPath()),StandardCharsets.UTF_8):"Display tests\n";
        SimpleDateFormat stamp=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String line=new String(StartupTrace.encode(stamp.format(new Date())+" run="+run+" "+text.replace('\u0000',' ')+"\n",16_000),StandardCharsets.UTF_8);
        byte[] bytes=(old+line).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_REPORT_BYTES){
            int first=bytes.length-MAX_REPORT_BYTES+64;
            while(first<bytes.length&&bytes[first]!='\n')first++;
            String tail=first<bytes.length?new String(bytes,first+1,bytes.length-first-1,StandardCharsets.UTF_8):"";
            bytes=StartupTrace.encode("Display tests [older history rotated]\n"+tail,MAX_REPORT_BYTES);
        }
        write(file("history"),bytes);
    }
    synchronized void capture(String run,String stage,String text) throws IOException {
        if(!stage.equals("before")&&!stage.equals("stereo")&&!stage.equals("restored"))throw new IllegalArgumentException("Unknown display test stage");
        if(!file("run").isFile()||!run.equals(new String(Files.readAllBytes(file("run").toPath()),StandardCharsets.UTF_8)))return;
        write(file(stage),DisplayDiagnostics.encode(DisplayDiagnostics.DISPLAY_STARTUP,"run="+run+" stage="+stage+"\n"+text));
    }
    synchronized byte[] report() throws IOException {
        return file("history").isFile()?StartupTrace.encode(new String(Files.readAllBytes(file("history").toPath()),StandardCharsets.UTF_8),MAX_REPORT_BYTES):new byte[0];
    }
    File captureFile(String stage) { return file(stage); }
    private void ensure() throws IOException { if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create display test storage"); }
    private static void write(File target,byte[] bytes) throws IOException {
        File temporary=new File(target.getPath()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);}
        if(!temporary.renameTo(target))throw new IOException("Cannot retain display test evidence");
    }
}

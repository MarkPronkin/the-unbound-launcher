package dev.beampro.bridge;

import android.os.ParcelFileDescriptor;
import dev.beampro.settings.DisplayDiagnostics;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Run in an isolated emulator under shell UID, using real dumpsys services and production pipes. */
public final class DisplayDiagnosticsDeviceChecks {
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000)throw new AssertionError("Run as ADB shell");
        DisplayDumpPipe dumps=new DisplayDumpPipe(2000);
        for(int kind:new int[]{DisplayDiagnostics.DISPLAY,DisplayDiagnostics.SURFACE_FLINGER}){
            byte[] bytes;
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(dumps.open(2000,kind));
                ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                bytes=output.toByteArray();
            }
            String report=new String(bytes,StandardCharsets.UTF_8);
            if(bytes.length>DisplayDiagnostics.MAX_BYTES||!report.contains("Identity: ADB shell helper")
                    ||!report.contains("exit=0; timedOut=false")||report.contains("Permission Denial"))
                throw new AssertionError("Real dump failed: "+report.substring(0,Math.min(1000,report.length())));
            String name=kind==0?"display":"surfaceflinger";
            try(FileOutputStream output=new FileOutputStream("/data/local/tmp/unbound-diagnostic-"+name+".txt")){output.write(bytes);}
            System.out.println("PASS real shell "+DisplayDiagnostics.service(kind)+" dump: "+bytes.length+" bytes");
        }
    }
}

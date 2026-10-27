import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Transplant the shipped SDK callback into an isolated Android fixture. */
public final class StartupFixtureDex {
    public static void main(String[] args)throws Exception {
        var shipped=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        var fixture=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));Method callback=null;
        for(ClassDef cls:shipped.getClasses())for(Method m:cls.getMethods())if(PatchGlassesStartupDex.target(m))callback=m;
        if(callback==null)throw new IllegalStateException("Missing actual SDK callback");
        List<ClassDef> classes=new ArrayList<>();int edits=0;
        for(ClassDef cls:fixture.getClasses()) {
            List<Method> methods=new ArrayList<>();
            for(Method m:cls.getMethods()) {
                if(cls.getType().equals(PatchGlassesStartupDex.SDK)&&m.getName().equals("runStartup")) {
                    methods.add(new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),
                        m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),callback.getImplementation()));edits++;
                } else methods.add(m);
            }
            classes.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),
                cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),methods));
        }
        if(edits!=1)throw new IllegalStateException("Missing fixture callback");
        DexPool.writeTo(args[2],new ImmutableDexFile(fixture.getOpcodes(),classes));
        System.out.println("Fixture executes the actual SDK callback implementation from the built APK");
    }
}

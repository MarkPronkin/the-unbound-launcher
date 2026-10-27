import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.util.*;

/** Execute real patched SDK guards and original phone lifecycle methods in an isolated app. */
public final class PresentationFixtureDex {
    public static void main(String[] args)throws Exception {
        var sdk=DexFileFactory.loadDexFile(args[0],Opcodes.forApi(33));
        var fixture=DexFileFactory.loadDexFile(args[1],Opcodes.forApi(33));
        Map<String,Set<String>> wanted=Map.of(
            "Lai/nreal/activitylife/NRXRApp;",Set.of("startUnityActivityInternal","moveToVirtualDisplay","readyForDynamicSwitchDPImpl","readyForRestartSessionImpl","getFakeActivity"),
            "Lai/nreal/nebula/space/LaunchSpaceAcrivity;",Set.of("onStart","onResume","onPause","onStop","onWindowFocusChanged"));
        Map<String,Method> methods=new HashMap<>();
        for(ClassDef cls:sdk.getClasses())if(wanted.containsKey(cls.getType()))for(Method m:cls.getMethods())
            if(wanted.get(cls.getType()).contains(m.getName()))methods.put(cls.getType()+m.getName(),m);
        if(methods.size()!=10)throw new IllegalStateException("Missing actual SDK methods");
        List<ClassDef> classes=new ArrayList<>();int edits=0;
        for(ClassDef cls:fixture.getClasses()) {
            List<Method> out=new ArrayList<>();
            for(Method m:cls.getMethods()) {
                Method original=methods.get(cls.getType()+m.getName());
                if(original!=null) {
                    if(!m.getParameterTypes().equals(original.getParameterTypes())||!m.getReturnType().equals(original.getReturnType()))throw new IllegalStateException("Fixture signature differs");
                    out.add(new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),original.getImplementation()));edits++;
                } else out.add(m);
            }
            classes.add(new ImmutableClassDef(cls.getType(),cls.getAccessFlags(),cls.getSuperclass(),cls.getInterfaces(),cls.getSourceFile(),cls.getAnnotations(),cls.getFields(),out));
        }
        if(edits!=10)throw new IllegalStateException("Missing fixture methods");
        DexPool.writeTo(args[2],new ImmutableDexFile(fixture.getOpcodes(),classes));
        System.out.println("Fixture uses five patched SDK entry guards and five original phone lifecycle methods");
    }
}

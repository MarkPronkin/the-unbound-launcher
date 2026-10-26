package dev.beampro.standalone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONObject;
import java.util.List;
import java.util.function.Consumer;

/** User-enabled public Accessibility API; input is restricted to the active test's apps. */
public final class PointerService extends AccessibilityService {
    private static PointerService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String[] allowed=new String[0];
    public static PointerService get() { return instance; }
    @Override protected void onServiceConnected() { instance=this; Results.init(this); Results.set("accessibility_connected",true); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No ongoing event/content logging. */ }
    @Override public void onInterrupt() { allowed=new String[0]; }
    @Override public void onDestroy() { if (instance==this) instance=null; super.onDestroy(); }
    public void allow(String... packages) { allowed=packages.clone(); }
    public void clear() { allowed=new String[0]; }
    private boolean permitted(String name) {
        for (String item:allowed) if(item.equals(name)) return true;
        return false;
    }
    public AccessibilityNodeInfo root(String name, int display) {
        if (!permitted(name)) return null;
        List<AccessibilityWindowInfo> windows=getWindowsOnAllDisplays().get(display);
        if (windows==null) return null;
        for (AccessibilityWindowInfo window:windows) {
            AccessibilityNodeInfo root=window.getRoot();
            if (root!=null && root.getPackageName()!=null && name.contentEquals(root.getPackageName())) return root;
        }
        return null;
    }
    public Rect bounds(String name, int display) {
        AccessibilityNodeInfo root=root(name,display);
        if (root==null) return null;
        Rect bounds=new Rect(); root.getBoundsInScreen(bounds); return bounds;
    }
    public org.json.JSONArray geometry() {
        org.json.JSONArray result=new org.json.JSONArray();
        android.util.SparseArray<List<AccessibilityWindowInfo>> displays=getWindowsOnAllDisplays();
        for(int i=0;i<displays.size();i++) for(AccessibilityWindowInfo window:displays.valueAt(i)) {
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null || root.getPackageName()==null || !permitted(root.getPackageName().toString())) continue;
            JSONObject item=new JSONObject(); Rect nodeBounds=new Rect(),windowBounds=new Rect();
            root.getBoundsInScreen(nodeBounds); window.getBoundsInScreen(windowBounds);
            Results.put(item,"package",root.getPackageName().toString()); Results.put(item,"display_id",displays.keyAt(i));
            Results.put(item,"node_bounds",nodeBounds.flattenToString()); Results.put(item,"window_bounds",windowBounds.flattenToString());
            Results.put(item,"window_id",window.getId());
            AccessibilityWindowInfo parent=window.getParent();
            if(parent!=null) {
                Rect parentBounds=new Rect(); parent.getBoundsInScreen(parentBounds);
                Results.put(item,"parent_bounds",parentBounds.flattenToString()); Results.put(item,"parent_id",parent.getId());
            }
            result.put(item);
        }
        return result;
    }
    public void tap(String name,int display,Rect target,Consumer<Boolean> done) {
        Rect app=bounds(name,display);
        if (app==null || target==null || target.isEmpty() || !app.contains(target.centerX(),target.centerY())) { done.accept(false); return; }
        Path path=new Path(); path.moveTo(target.centerX(),target.centerY());
        GestureDescription gesture=new GestureDescription.Builder().setDisplayId(display)
                .addStroke(new GestureDescription.StrokeDescription(path,0,80)).build();
        boolean accepted=dispatchGesture(gesture,new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) { done.accept(true); }
            @Override public void onCancelled(GestureDescription gesture) { done.accept(false); }
        },handler);
        if (!accepted) done.accept(false);
    }
    public void selfCheck(Rect button,java.util.function.Consumer<Boolean> done) {
        allow(getPackageName()); tap(getPackageName(),0,button,done);
    }
    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo node,String text) {
        if (node==null) return null;
        if (node.getText()!=null && text.contentEquals(node.getText())) return node;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo found=findText(node.getChild(i),text); if(found!=null) return found;
        }
        return null;
    }
    private static long treeHash(AccessibilityNodeInfo node) {
        if(node==null) return 0;
        long value=String.valueOf(node.getText()).hashCode()*31L+String.valueOf(node.getViewIdResourceName()).hashCode();
        for(int i=0;i<node.getChildCount();i++) value=value*31+treeHash(node.getChild(i));
        return value;
    }
    public void checkRealApp(String name, int display, Consumer<JSONObject> done) {
        JSONObject result=new JSONObject(); Results.put(result,"package",name); Results.put(result,"display_id",display);
        AccessibilityNodeInfo root=root(name,display);
        Results.put(result,"target_visible",root!=null);
        AccessibilityNodeInfo target=null;
        if (root!=null && name.equals("com.android.chrome")) {
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar");
            if (!nodes.isEmpty()) target=nodes.get(0);
        } else if(root!=null && name.equals("com.finalwire.aida64")) target=findText(root,"CPU");
        if (target==null) { Results.put(result,"error","No known safe test control found"); done.accept(result); return; }
        long before=treeHash(root); Rect rectangle=new Rect(); target.getBoundsInScreen(rectangle);
        Results.put(result,"control",name.equals("com.android.chrome")?"address bar":"CPU");
        tap(name,display,rectangle,completed -> {
            Results.put(result,"gesture_completed",completed);
            handler.postDelayed(() -> {
                AccessibilityNodeInfo after=root(name,display);
                Results.put(result,"content_changed",after!=null && treeHash(after)!=before);
                boolean focused=false;
                if(after!=null) {
                    AccessibilityNodeInfo focus=after.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    focused=focus!=null && focus.isFocused();
                }
                Results.put(result,"input_focused",focused);
                boolean observed=name.equals("com.android.chrome")?focused:after!=null&&treeHash(after)!=before;
                Results.put(result,"observed_response",completed&&observed);
                if(completed && focused && name.equals("com.android.chrome")) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    handler.postDelayed(() -> {
                        if(!permitted(name)) return;
                        AccessibilityNodeInfo current=root(name,display);
                        AccessibilityNodeInfo focus=current==null?null:current.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        if(focus!=null && focus.isFocused()) performGlobalAction(GLOBAL_ACTION_BACK);
                    },350);
                }
                done.accept(result);
            },1200);
        });
    }
}

package ai.nreal.extension.virtualcontroller;
import android.app.Fragment;
import android.os.Bundle;
import android.view.*;
public class TouchPadPortraitFragment extends Fragment {
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
        View view = new View(getActivity()); view.setBackgroundColor(0xff151515); view.setOnTouchListener((v,event)->true); return view;
    }
}

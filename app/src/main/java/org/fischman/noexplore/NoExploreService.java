package org.fischman.noexplore;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NoExploreService extends AccessibilityService {
    private final boolean DEBUG = false;

    private void emit(String msg) { if (DEBUG) Log.e("AMI", msg); }

    // Time (epoch millis) of the last event that was acted on for each type.
    // Used to throttle actions, in preference to a longer android:notificationTimeout value because
    // that also slows down the first action instead of only subsequent ones.
    private Map<Integer, Long> lastEventTime = new HashMap<Integer, Long>();

    private TextView obscuringView;

    public NoExploreService() {}

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            Log.e("AMI", "getServiceInfo() returned null!");
            return;
        }
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 1F));
        tv.setGravity(Gravity.CENTER);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tv.setText("\"Suggested posts\" being obscured by " + getString(R.string.app_name));
        tv.setTextColor(Color.YELLOW);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextSize(35F);
        obscuringView = tv;
    }

    private static String emptyIfNull(CharSequence s) {  return s != null ? s.toString() : ""; }

    // Handy for debugging.
    private void dumpRecursively(AccessibilityNodeInfo node) {
        dumpRecursively("", node);
    }
    private void dumpRecursively(String prefix, AccessibilityNodeInfo node) {
        if (!DEBUG || node == null) return;
        emit(prefix + node.getText() + " - " + node.getHintText() + " - " + node.getTooltipText() + " - " + node.getViewIdResourceName());
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            dumpRecursively(prefix + "  ", node.getChild(i));
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_ACTIVE) != 0) {
                emit("hideObscuringOverlay because TYPE_WINDOWS_CHANGED from null package");
                hideObscuringOverlay();
            }
            return;
        }

        long eventTime = event.getEventTime();
        if (eventTime - lastEventTime.getOrDefault(event.getEventType(), 0L) < 500) return;
        if (DEBUG) emit("event is " + event);

        switch (event.getPackageName().toString()) {
            case "com.instagram.android":
                onInstagramEvent(event);
                return;
            case "com.google.android.apps.maps":
                onMapsEvent(event);
                return;
            default:
                return;
        }
    }

    private AccessibilityNodeInfo firstDescendant(AccessibilityNodeInfo source, String viewID) {
        if (source == null) return null;
        List<AccessibilityNodeInfo> candidates = source.findAccessibilityNodeInfosByViewId(viewID);
        if (candidates.isEmpty()) return null;
        if (candidates.size() > 1 && DEBUG)
            emit("Multiple descendants with view ID " + viewID + ": " + candidates);
        AccessibilityNodeInfo found = candidates.iterator().next();
        if (!found.isVisibleToUser()) return null;
        return found;
    }

    private void onMapsEvent(AccessibilityEvent event) {
        long eventTime = event.getEventTime();
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo found = firstDescendant(event.getSource(), "com.google.android.apps.maps:id/explore_tab_home_title_card");
        if (found == null) return;
        Rect r = new Rect();
        found.getBoundsInWindow(r);
        // Don't process the view until it's popped up enough for drag-down to be meaningful.
        if (r.top > 2000) return;
        // Don't process the view if its bounds don't make sense (e.g. overlapping notification bar).
        if (r.top < 10) return;

        lastEventTime.put(eventType, eventTime);
        Path path = new Path();
        path.moveTo((r.left+r.right)/2, r.top+5);
        path.lineTo((r.left+r.right)/2, r.top+200);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
                .build();
        Boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) { emit("Gesture completed"); }
            @Override
            public void onCancelled(GestureDescription gestureDescription) { emit("Gesture cancelled"); }
        }, null);
        emit("Dispatch gesture for maps: " + dispatched);
        if (DEBUG) {
            emit("Gesture because saw maps \"Latest in\" in source, which follows after source: " + event.getSource());
            dumpRecursively(event.getSource());
        }
    }

    private int profileTabTop = -1;
    private int profileTabTop(AccessibilityNodeInfo source) {
        if (profileTabTop > 0) { return profileTabTop; }
        AccessibilityNodeInfo profileTab = firstDescendant(source, "com.instagram.android:id/profile_tab");
        if (profileTab == null) return 0;
        Rect rect = new Rect();
        profileTab.getBoundsInWindow(rect);
        emit("profileTab: " + rect);
        profileTabTop = rect.top;
        return profileTabTop;
    }


    private void onInstagramEvent(AccessibilityEvent event) {
        long eventTime = event.getEventTime();
        int eventType = event.getEventType();

        // TYPE_VIEW_SELECTED is delivered a bit faster than TYPE_VIEW_CLICK so use that, but also
        // is delivered multiple times, hence the eventTime-based throttling.
        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            AccessibilityNodeInfo search = firstDescendant(event.getSource(), "com.instagram.android:id/search_tab");
            AccessibilityNodeInfo reels = firstDescendant(event.getSource(), "com.instagram.android:id/clips_tab");
            if (search != null || reels != null) {
                lastEventTime.put(eventType, eventTime);
                if (DEBUG) emit("BACK because search or reels is not null: " + search + ", " + reels);
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo found = firstDescendant(event.getSource(), "com.instagram.android:id/end_of_feed_demarcator_container");
        if (found == null) { hideObscuringOverlay(); return; }
        Rect r = new Rect();
        found.getBoundsInScreen(r);
        lastEventTime.put(eventType, eventTime);
        emit("Obscuring view because end_of_feed_demarcator is at " + r);
        showObscuringOverlay(r.bottom, profileTabTop(event.getSource()));
    }

    private WindowManager getWindowManager() { return (WindowManager) getSystemService(WINDOW_SERVICE); }
    private DisplayManager getDisplayManager() { return (DisplayManager) getSystemService(DISPLAY_SERVICE); }
    private void hideObscuringOverlay() {
        emit("hideObscuringOverlay");
        if (obscuringView.getParent() == null) {
            emit("hideObscuringOverlay no-op'd");
            return;
        }
        getWindowManager().removeView(obscuringView);
    }

    private void showObscuringOverlay(int top, int bottom) {
        emit("showObscuringOverlay: " + top +" - " + bottom);
        // Force always being able to see a strip at the top for scrolling stories back into view.
        if (top < 400) top = 400;
        WindowManager wm = getWindowManager();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.format = PixelFormat.OPAQUE;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.width = MATCH_PARENT;
        lp.height = bottom - top; // wm.getMaximumWindowMetrics().getBounds().height() - top - bottom;
        lp.gravity = Gravity.BOTTOM;
        lp.y = wm.getMaximumWindowMetrics().getBounds().height() - bottom;
        if (obscuringView.getParent() == null) {
            emit("  showObscuringOverlay - addView");
            wm.addView(obscuringView, lp);
        } else {
            emit("  showObscuringOverlay - updateView");
            wm.updateViewLayout(obscuringView, lp);
        }
    }

    @Override
    public void onInterrupt() {}
}

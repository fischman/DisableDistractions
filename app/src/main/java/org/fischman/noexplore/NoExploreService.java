package org.fischman.noexplore;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class NoExploreService extends AccessibilityService {
    private final boolean DEBUG = false;

    private void emit(String msg) {
        Log.e("AMI", msg);
    }

    // Time (epoch millis) of the last event that was acted on.
    // Used to throttle actions, in preference to a longer android:notificationTimeout value because
    // that also slows down the first action instead of only subsequent ones.
    private long lastEventTime = 0;

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
        setServiceInfo(info);
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
        long eventTime = event.getEventTime();
        if (eventTime - lastEventTime < 500) return;
        if (DEBUG) emit("event is " + event);

        switch (emptyIfNull(event.getPackageName())) {
            case "com.instagram.android":
                onInstagramEvent(event);
                return;
            case "com.google.android.apps.maps":
                onMapsEvent(event);
                return;
            default:
                // Should be excluded by accessibility_service_config.xml configuration!
                throw new RuntimeException("Unexpected event package name: " + event);
        }
    }

    private AccessibilityNodeInfo firstDescendant(AccessibilityNodeInfo source, String viewID) {
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
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) { return; }
        AccessibilityNodeInfo found = firstDescendant(source, "com.google.android.apps.maps:id/explore_tab_home_title_card");
        if (found == null) return;
        Rect r = new Rect();
        found.getBoundsInScreen(r);
        // Don't process the view until it's popped up enough for drag-down to be meaningful.
        if (r.top > 2000) return;
        // Don't process the view if its bounds don't make sense (e.g. overlapping notification bar).
        if (r.top < 10) return;

        lastEventTime = eventTime;
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
            emit("Gesture because saw maps \"Latest in\" in source, which follows after source: " + source);
            dumpRecursively(source);
        }
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
                lastEventTime = eventTime;
                if (DEBUG) emit("BACK because search or reels is not null: " + search + ", " + reels);
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) { return; }
        AccessibilityNodeInfo found = firstDescendant(source, "com.instagram.android:id/end_of_feed_demarcator_container");
        if (found == null) return;
        Rect r = new Rect();
        found.getBoundsInScreen(r);
        // Arbitrary choice of "1000" below, but useful to prevent the app being unusable if there
        // are no new posts so "Suggested Posts" shows up at the top of the feed.
        if (r.top > 1000) {
            lastEventTime = eventTime;
            if (DEBUG) {
                emit("UP because saw Suggested Posts in source at " + r + ", which follows after source: " + source);
                dumpRecursively(source);
            }
            performGlobalAction(GESTURE_SWIPE_UP);
        }
    }

    @Override
    public void onInterrupt() {}
}

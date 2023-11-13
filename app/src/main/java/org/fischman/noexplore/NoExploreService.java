package org.fischman.noexplore;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private static String emptyIfNull(CharSequence s) {  return s != null ? s.toString() : ""; }

    private static Boolean containsAny(CharSequence haystack, String[] needles) {
        String haystackString = emptyIfNull(haystack);
        for (String needle: needles) {
            if (haystackString.contains(needle)) return true;
        }
        return false;
    }

    // Returns the first-encountered descendant of |node| that contains
    // any member of |needles|, or null if no match.
    private AccessibilityNodeInfo containsRecursively(AccessibilityNodeInfo node, String[] needles) {
        if (node == null) return null;
        if (containsAny(node.getText(), needles) ||
            containsAny(node.getHintText(), needles) ||
            containsAny(node.getTooltipText(), needles)) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            AccessibilityNodeInfo found = containsRecursively(node.getChild(i), needles);
            if (found != null) return found;
        }
        return null;
    }

    // Handy for debugging.
    private void dumpRecursively(AccessibilityNodeInfo node) {
        if (!DEBUG || node == null) return;
        emit(node.getText() + " - " + node.getHintText() + " - " + node.getTooltipText());
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            dumpRecursively(node.getChild(i));
        }
    }

    private static final String[] suggestedInstagram = {"Suggested Posts", "Suggested for you"};
    private static final String[] mapsExplore = {"Latest in "};

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

    private void onMapsEvent(AccessibilityEvent event) {
        long eventTime = event.getEventTime();
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) { return; }
        AccessibilityNodeInfo found = containsRecursively(source, mapsExplore);
        if (found == null) return;
        Rect r = new Rect();
        found.getBoundsInScreen(r);

        lastEventTime = eventTime;
        Path path = new Path();
        path.moveTo(r.left + (r.right-r.left)/2, r.top+5);
        path.lineTo(r.left + (r.right-r.left)/2, r.top+200);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 10))
                .build();
        Boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) { Log.e("AMI", "Gesture completed"); }
            @Override
            public void onCancelled(GestureDescription gestureDescription) { Log.e("AMI", "Gesture cancelled"); }
        }, null);
        emit("Dispatch gesture for maps: " + dispatched);
        if (DEBUG) emit("Gesture because saw maps \"Latest in\" in source, which follows after source: " + source);
        if (DEBUG) dumpRecursively(source);
    }

    private void onInstagramEvent(AccessibilityEvent event) {
        long eventTime = event.getEventTime();
        int eventType = event.getEventType();
        // TYPE_VIEW_SELECTED is delivered a bit faster than TYPE_VIEW_CLICK so use that, but also
        // is delivered multiple times, hence the eventTime-based throttling.
        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            String description = emptyIfNull(event.getContentDescription());
            if ("Search and explore".equals(description) || "Reels".equals(description)) {
                lastEventTime = eventTime;
                if (DEBUG) emit("BACK because description is " + description);
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) { return; }
        // Arbitrary choice of "1000" below, but useful to prevent the app being unusable if there
        // are no new posts so "Suggested Posts" shows up at the top of the feed.
        AccessibilityNodeInfo found = containsRecursively(source, suggestedInstagram);
        if (found == null) return;
        Rect r = new Rect();
        found.getBoundsInScreen(r);
        if (r.top > 1000) {
            lastEventTime = eventTime;
            if (DEBUG) emit("UP because saw Suggested Posts in source, which follows after source: " + source);
            if (DEBUG) dumpRecursively(source);
            performGlobalAction(GESTURE_SWIPE_UP);
        }
    }

    @Override
    public void onInterrupt() {}
}

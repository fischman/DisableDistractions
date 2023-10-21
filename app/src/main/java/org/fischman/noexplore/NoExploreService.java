package org.fischman.noexplore;

import android.accessibilityservice.AccessibilityService;
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

    private String emptyIfNull(CharSequence s) {  return s != null ? s.toString() : ""; }

    // Returns the top of the rect of the first-encountered descendant of |node| that contains
    // |needle|, or -1 if no match.
    private int containsRecursively(AccessibilityNodeInfo node, String needle) {
        if (node == null) return -1;
        if (emptyIfNull(node.getText()).contains(needle) ||
            emptyIfNull(node.getHintText()).contains(needle) ||
            emptyIfNull(node.getTooltipText()).contains(needle)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            return r.top;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            int found = containsRecursively(node.getChild(i), needle);
            if (found >= 0) return found;
        }
        return -1;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long eventTime = event.getEventTime();
        if (eventTime - lastEventTime < 500) { return; }
        if (!"com.instagram.android".equals(event.getPackageName())) {
            // Should be excluded by XML configuration!
            throw new RuntimeException("Unexpected event package name: " + event);
        }
        if (DEBUG) emit("event is " + event);
        int eventType = event.getEventType();
        // TYPE_VIEW_SELECTED is delivered a bit faster than TYPE_VIEW_CLICK so use that, but also
        // is delivered multiple times, hence the eventTime-based throttling.
        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            CharSequence description = event.getContentDescription();
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
        // Arbitrary choice of "1000" below, but useful to prevent unusability of the app if there
        // are no new posts so "Suggested Posts" shows up at the top of the feed.
        if (containsRecursively(source, "Suggested Posts") > 1000) {
            lastEventTime = eventTime;
            if (DEBUG) emit("UP because saw Suggested Posts in source, which follows after source: " + source);
            if (DEBUG) dumpRecursively(source);
            performGlobalAction(GESTURE_SWIPE_UP);
        }
    }

    @Override
    public void onInterrupt() {}
}

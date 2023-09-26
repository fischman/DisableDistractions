package org.fischman.noexplore;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class NoExploreService extends AccessibilityService {
    public NoExploreService() {}

    private String emptyIfNull(CharSequence s) {  return s != null ? s.toString() : ""; }
    private boolean containsRecursively(AccessibilityNodeInfo node, String needle) {
        if (node == null) return false;
        if (emptyIfNull(node.getText()).contains(needle) ||
            emptyIfNull(node.getHintText()).contains(needle) ||
            emptyIfNull(node.getTooltipText()).contains(needle)) {
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            if (containsRecursively(node.getChild(i), needle)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!"com.instagram.android".equals(event.getPackageName())) {
            // Should be excluded by XML configuration!
            throw new RuntimeException("Unexpected event package name: " + event);
        }
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CharSequence description = event.getContentDescription();
            if ("Search and explore".equals(description) || "Reels".equals(description)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }
        }
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) { return; }
        if (containsRecursively(source, "Suggested Posts"))
            performGlobalAction(GESTURE_SWIPE_UP);
    }

    @Override
    public void onInterrupt() {}
}

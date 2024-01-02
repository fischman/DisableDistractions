package org.fischman.disabledistractions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class DisableDistractionsActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, 0)
    }
}

class DisableDistractionsService : AccessibilityService() {
    private val debug = false
    private fun emit(msg: String) {
        if (debug) Log.e("AMI", msg)
    }

    // Time (epoch millis) of the last event that was acted on for each type.
    // Used to throttle actions, in preference to a longer android:notificationTimeout value because
    // that also slows down the first action instead of only subsequent ones.
    private val lastEventTime: MutableMap<Int, Long> = HashMap()
    private var obscuringView: TextView? = null

    @SuppressLint("SetTextI18n")
    public override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        if (info == null) {
            Log.e("AMI", "getServiceInfo() returned null!")
            return
        }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        val tv = TextView(this)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
        tv.gravity = Gravity.CENTER
        tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
        tv.text = "Content being obscured by " + getString(R.string.app_name)
        tv.setTextColor(Color.YELLOW)
        tv.setBackgroundColor(0xDD shl 24)
        tv.textSize = 35f
        obscuringView = tv
    }

    // Handy for debugging.
    private fun dumpRecursively(node: AccessibilityNodeInfo?) {
        dumpRecursively("", node)
    }

    private fun dumpRecursively(prefix: String, node: AccessibilityNodeInfo?) {
        if (!debug || node == null) return
        emit(prefix + node.text + " - " + node.hintText + " - " + node.tooltipText + " - " + node.viewIdResourceName)
        val count = node.childCount
        for (i in 0 until count) {
            dumpRecursively("$prefix  ", node.getChild(i))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == null) {
            if ((event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                (((event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_ACTIVE) != 0) or
                 ((event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_LAYER) != 0))
            ) {
                emit("hideObscuringOverlay because TYPE_WINDOWS_CHANGED from null package")
                hideObscuringOverlay()
            }
            return
        }
        val eventTime = event.eventTime
        if (eventTime - lastEventTime.getOrDefault(event.eventType, 0L) < 500) return
        if (debug) emit("event is $event")
        when (event.packageName.toString()) {
            "com.instagram.android" -> {
                onInstagramEvent(event)
                return
            }

            "com.google.android.apps.maps" -> {
                onMapsEvent(event)
                return
            }

            else -> return
        }
    }

    private fun firstDescendant(
        source: AccessibilityNodeInfo?,
        viewID: String
    ): AccessibilityNodeInfo? {
        return firstDescendant(source, viewID, "")
    }

    private fun firstDescendant(
            source: AccessibilityNodeInfo?,
            viewID: String,
            needle: String
        ): AccessibilityNodeInfo? {
        if (source == null) return null
        var candidates = source.findAccessibilityNodeInfosByViewId(viewID)
        candidates = candidates.filter { it.isVisibleToUser }
        if (needle != "") {
            candidates = candidates.filter { (it.text?:"").contains(needle) }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size > 1 && debug) emit("Multiple descendants with view ID $viewID: $candidates")
        return candidates.iterator().next()
    }

    private fun onMapsEvent(event: AccessibilityEvent) {
        val eventTime = event.eventTime
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val found = firstDescendant(
            event.source,
            "com.google.android.apps.maps:id/explore_tab_home_title_card"
        )
            ?: return
        val r = Rect()
        found.getBoundsInScreen(r)
        // Don't process the view until it's popped up enough for drag-down to be meaningful.
        if (r.top > 2000) return
        // Don't process the view if its bounds don't make sense (e.g. overlapping notification bar).
        if (r.top < 10) return
        lastEventTime[eventType] = eventTime
        val path = Path()
        path.moveTo(((r.left + r.right) / 2).toFloat(), (r.top + 5).toFloat())
        path.lineTo(((r.left + r.right) / 2).toFloat(), (r.top + 200).toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0, 1))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                emit("Gesture completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                emit("Gesture cancelled")
            }
        }, null)
        emit("Dispatch gesture for maps: $dispatched")
        if (debug) {
            emit("Gesture because saw maps \"Latest in\" in source, which follows after source: " + event.source)
            dumpRecursively(event.source)
        }
    }

    private var profileTabTop = -1
    private fun profileTabTop(source: AccessibilityNodeInfo?): Int {
        if (profileTabTop > 0) {
            return profileTabTop
        }
        val profileTab = firstDescendant(source, "com.instagram.android:id/profile_tab") ?: return 0
        val rect = Rect()
        profileTab.getBoundsInScreen(rect)
        profileTabTop = rect.top
        return profileTabTop
    }

    private val instagramViewsToHide = mapOf(
        "com.instagram.android:id/end_of_feed_demarcator_container" to "", // "Suggested for you"
        "com.instagram.android:id/row_right_aligned_follow_button_stub" to "Follow", // "Follow" button on non-friend posts.
        "com.instagram.android:id/netego_bloks_view" to "", // Threads inter-app up-sell.
        "com.instagram.android:id/secondary_label" to "Sponsored", // Sponsored posts.
        "com.instagram.android:id/netego_carousel_title" to "Suggested for you", // Suggestions for users to follow.
    )

    private fun onInstagramEvent(event: AccessibilityEvent) {
        val eventTime = event.eventTime
        val eventType = event.eventType

        // TYPE_VIEW_SELECTED is delivered a bit faster than TYPE_VIEW_CLICK so use that, but also
        // is delivered multiple times, hence the eventTime-based throttling.
        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            val search = firstDescendant(event.source, "com.instagram.android:id/search_tab")
            val reels = firstDescendant(event.source, "com.instagram.android:id/clips_tab")
            if (search != null || reels != null) {
                lastEventTime[eventType] = eventTime
                if (debug) emit("BACK because search or reels is not null: $search, $reels")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        dumpRecursively("SFY: ", event.source)

        var minRect = Rect()
        for ((id, needle) in instagramViewsToHide) {
            val found = firstDescendant(event.source, id, needle) ?: continue
            val r = Rect()
            found.getBoundsInScreen(r)
            emit("Obscuring view because $id is at $r")
            if (minRect.height() == 0 || minRect.top > r.top) { minRect = r }
        }
        if (minRect.height() == 0) {
            hideObscuringOverlay()
        } else {
            lastEventTime[eventType] = eventTime
            showObscuringOverlay(minRect.top, profileTabTop(event.source))
        }
    }

    private val windowManager: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun hideObscuringOverlay() {
        emit("hideObscuringOverlay")
        if (obscuringView!!.parent == null) {
            emit("hideObscuringOverlay suppressed")
            return
        }
        windowManager.removeView(obscuringView)
    }

    private fun showObscuringOverlay(top: Int, bottom: Int) {
        @Suppress("NAME_SHADOWING") var top = top
        if (top + 100 >= bottom) {
            emit("Not enough to obscure between $top and $bottom, so skipping")
            return
        }
        emit("showObscuringOverlay: $top - $bottom")
        // Force always being able to see a strip at the top for scrolling stories back into view.
        if (top < 400) top = 400
        val wm = windowManager
        val lp = WindowManager.LayoutParams()
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = bottom - top
        lp.gravity = Gravity.BOTTOM
        lp.y = wm.maximumWindowMetrics.bounds.height() - bottom
        if (obscuringView!!.parent == null) {
            emit("  showObscuringOverlay - addView")
            wm.addView(obscuringView, lp)
        } else {
            emit("  showObscuringOverlay - updateView")
            wm.updateViewLayout(obscuringView, lp)
        }
    }

    override fun onInterrupt() {}
}
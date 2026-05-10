package com.swip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SwipAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SwipAccessibilityService? = null
        var skipVisible = false

        private val SKIP_LABELS = listOf(
            "skip ad", "skip ads", "skip »", "skip"
        )
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = arrayOf("com.google.android.youtube")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        sendBroadcast(Intent("swip.ACCESSIBILITY_ON"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != "com.google.android.youtube") return
        try {
            val root = rootInActiveWindow ?: return
            val node = findSkip(root)
            val nowVisible = node != null
            if (nowVisible != skipVisible) {
                skipVisible = nowVisible
                sendBroadcast(Intent(if (nowVisible) "swip.SKIP_VISIBLE" else "swip.SKIP_GONE"))
            }
        } catch (_: Exception) {}
    }

    private fun findSkip(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val t = node.text?.toString()?.lowercase() ?: ""
            val d = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (node.isClickable && (
                SKIP_LABELS.any { t.contains(it) || d.contains(it) } ||
                id.contains("skip_ad") || id.contains("skip_button"))) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = findSkip(node.getChild(i) ?: continue)
                if (found != null) return found
            }
        } catch (_: Exception) {}
        return null
    }

    fun doSkip(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val node = findSkip(root) ?: return false
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) sendBroadcast(Intent("swip.AD_SKIPPED"))
            result
        } catch (_: Exception) { false }
    }

    override fun onInterrupt() { instance = null }
    override fun onDestroy() { super.onDestroy(); instance = null }
}

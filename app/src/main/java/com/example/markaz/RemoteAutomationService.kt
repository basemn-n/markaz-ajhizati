package com.example.markaz

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * UI-test helper scoped to Markaz Ajhizati itself.
 * It records event metadata only; it never reads node text or other apps' windows.
 */
class RemoteAutomationService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() != packageName) return

        Log.d(
            TAG,
            "UI event: type=${event.eventType}, class=${event.className ?: "unknown"}"
        )
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility automation interrupted")
    }

    companion object {
        private const val TAG = "MarkazUiAutomation"
    }
}

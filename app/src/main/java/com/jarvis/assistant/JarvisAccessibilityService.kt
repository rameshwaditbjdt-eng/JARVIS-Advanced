package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Lets JARVIS perform simple, user-requested actions in the currently visible app.
 * The phone owner must explicitly enable this service in Android Accessibility settings.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeService: JarvisAccessibilityService? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun isEnabled(): Boolean = activeService != null

        /**
         * Queues an action on Android's main thread. False means the accessibility
         * service has not been enabled by the user.
         */
        fun perform(command: String): Boolean {
            val service = activeService ?: return false
            mainHandler.post { service.performCommand(command) }
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are intentionally not collected or stored.
    }

    override fun onInterrupt() {
        // No ongoing gesture needs to be interrupted.
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun performCommand(rawCommand: String) {
        val command = rawCommand.lowercase(Locale.US).trim()

        when {
            command == "scroll down" || command == "scroll" -> scroll(true)
            command == "scroll up" -> scroll(false)
            command == "go back" || command == "press back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            command == "go home" || command == "press home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            command == "show recent apps" || command == "open recent apps" ->
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            command.startsWith("tap ") || command.startsWith("click ") -> {
                val target = command.substringAfter(' ').trim()
                if (target.isNotEmpty()) clickText(target)
            }
            command.startsWith("type ") -> {
                val text = rawCommand.substringAfter(' ').trim()
                if (text.isNotEmpty()) typeText(text)
            }
        }
    }

    private fun scroll(forward: Boolean) {
        val root = rootInActiveWindow ?: return
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (!root.performAction(action)) {
            performGlobalAction(if (forward) GLOBAL_ACTION_DOWN else GLOBAL_ACTION_UP)
        }
    }

    private fun clickText(target: String) {
        val root = rootInActiveWindow ?: return
        val node = findMatchingNode(root, target) ?: return
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (text.contains(target, ignoreCase = true) ||
            description.contains(target, ignoreCase = true)
        ) return node

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findMatchingNode(child, target)
            if (match != null) return match
        }
        return null
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!focused.isEditable) return

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}

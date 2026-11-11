package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs simple user-requested actions in the visible app.
 * The phone owner must explicitly enable this service in Android Accessibility settings.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeService: JarvisAccessibilityService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun isEnabled(): Boolean = activeService != null

        /**
         * Runs the UI action on Android's main thread and reports whether it succeeded.
         * The short wait lets JARVIS give an honest spoken result instead of claiming
         * success merely because the accessibility service is enabled.
         */
        fun perform(command: String): Boolean {
            val service = activeService ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return service.performCommand(command)
            }

            val completed = CountDownLatch(1)
            val succeeded = AtomicBoolean(false)
            mainHandler.post {
                try {
                    succeeded.set(service.performCommand(command))
                } finally {
                    completed.countDown()
                }
            }
            return try {
                completed.await(2, TimeUnit.SECONDS) && succeeded.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun performCommand(rawCommand: String): Boolean {
        val command = rawCommand.lowercase(Locale.US).trim()
        return when {
            command == "scroll down" || command == "scroll" -> scroll(true)
            command == "scroll up" -> scroll(false)
            command == "go back" || command == "press back" ->
                performGlobalAction(GLOBAL_ACTION_BACK)
            command == "go home" || command == "press home" ->
                performGlobalAction(GLOBAL_ACTION_HOME)
            command == "show recent apps" || command == "open recent apps" ->
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            command.startsWith("tap ") || command.startsWith("click ") -> {
                val target = command.substringAfter(' ').trim()
                target.isNotEmpty() && clickText(target)
            }
            command.startsWith("type ") -> {
                val text = rawCommand.substringAfter(' ').trim()
                text.isNotEmpty() && typeText(text)
            }
            else -> false
        }
    }

    private fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (root.isScrollable && root.performAction(action)) return true
        return performOnScrollableNode(root, action)
    }

    private fun performOnScrollableNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (child.isScrollable && child.performAction(action)) return true
            if (performOnScrollableNode(child, action)) return true
        }
        return false
    }

    private fun clickText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, target) ?: return false
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        return (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

    private fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}

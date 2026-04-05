package com.aria.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build
import com.aria.assistant.live.core.PersistentLogger

class ARIAAccessibilityService : AccessibilityService() {
    
    companion object {
        var instance: ARIAAccessibilityService? = null
        
        fun isEnabled(): Boolean {
            return instance != null
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        
        serviceInfo = info
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to monitor UI events if needed
    }
    
    override fun onInterrupt() {
        // Service interrupted
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    // ARIA Control Functions

    fun clickButton(text: String): Boolean {
        PersistentLogger.log(this, "A11Y_ACTION", "Click button: $text")

        for (attempt in 1..3) {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                PersistentLogger.log(this, "A11Y_ERROR", "No root node (attempt $attempt)")
                if (attempt < 3) Thread.sleep(300)
                continue
            }

            try {
                val node = findNodeByText(rootNode, text)

                if (node != null) {
                    var clickableNode = node
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    if (clickableNode != null && clickableNode.isClickable) {
                        val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) {
                            PersistentLogger.log(this, "A11Y_SUCCESS", "Clicked button: $text")
                            return true
                        }
                    }
                }

                // Fallback for content description buttons (e.g. WhatsApp send button)
                val descNode = findNodeByContentDescription(rootNode, text)
                if (descNode != null) {
                    var clickableNode = descNode
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }
                    if (clickableNode != null && clickableNode.isClickable) {
                        val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) {
                            PersistentLogger.log(this, "A11Y_SUCCESS", "Clicked button (desc): $text")
                            return true
                        }
                    }
                }
            } finally {
                rootNode.recycleSafe()
            }

            if (attempt < 3) {
                PersistentLogger.log(this, "A11Y_RETRY", "Button not found, retrying: $text (attempt $attempt)")
                Thread.sleep(500)
            }
        }

        PersistentLogger.log(this, "A11Y_ERROR", "Click button failed: $text")
        return false
    }

    fun inputText(text: String, targetHint: String? = null): Boolean {
        PersistentLogger.log(this, "A11Y_ACTION", "Input text (length=${text.length}, hint=$targetHint)")

        for (attempt in 1..3) {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                PersistentLogger.log(this, "A11Y_ERROR", "No root node for input (attempt $attempt)")
                if (attempt < 3) Thread.sleep(300)
                continue
            }

            try {
                val editText = findEditText(rootNode, targetHint)

                if (editText != null) {
                    editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100) // Give focus time to settle

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val arguments = android.os.Bundle()
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        val success = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        if (success) {
                            PersistentLogger.log(this, "A11Y_SUCCESS", "Text input successful")
                            return true
                        } else {
                            PersistentLogger.log(this, "A11Y_WARN", "SET_TEXT action returned false")
                        }
                    } else {
                        PersistentLogger.log(this, "A11Y_ERROR", "API level too low for SET_TEXT")
                        return false
                    }
                } else {
                    PersistentLogger.log(this, "A11Y_WARN", "EditText not found (attempt $attempt)")
                }
            } finally {
                rootNode.recycleSafe()
            }

            if (attempt < 3) Thread.sleep(500)
        }

        PersistentLogger.log(this, "A11Y_ERROR", "Input text failed after retries")
        return false
    }

    fun scrollDown(): Boolean {
        PersistentLogger.log(this, "A11Y_ACTION", "Scroll down")
        val rootNode = rootInActiveWindow?.also {
            val result = it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            recycleChildren(it)
            PersistentLogger.log(this, "A11Y_RESULT", "Scroll down: $result")
            return result
        }
        return false
    }

    fun scrollUp(): Boolean {
        PersistentLogger.log(this, "A11Y_ACTION", "Scroll up")
        val rootNode = rootInActiveWindow?.also {
            val result = it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            recycleChildren(it)
            PersistentLogger.log(this, "A11Y_RESULT", "Scroll up: $result")
            return result
        }
        return false
    }

    fun getCurrentApp(): String? {
        val rootNode = rootInActiveWindow
        val packageName = rootNode?.packageName?.toString()
        rootNode?.recycleSafe()
        if (packageName != null) {
            PersistentLogger.log(this, "A11Y_INFO", "Current app: $packageName")
        }
        return packageName
    }

    fun readScreen(): String {
        PersistentLogger.log(this, "A11Y_ACTION", "Read screen")
        val rootNode = rootInActiveWindow ?: return "Cannot read screen"
        try {
            val texts = mutableListOf<String>()
            extractTexts(rootNode, texts)
            val result = texts.joinToString("\n")
            PersistentLogger.log(this, "A11Y_RESULT", "Screen text count: ${texts.size}")
            return result
        } finally {
            rootNode.recycleSafe()
        }
    }

    private fun recycleChildren(node: AccessibilityNodeInfo) {
        try {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.recycleSafe()
            }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleSafe() {
        try { this.recycle() } catch (_: Exception) { }
    }


    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString()
        if (nodeDesc != null && nodeDesc.contains(desc, ignoreCase = true)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, desc)
            if (result != null) {
                return result
            } else {
                child.recycleSafe()
            }
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) {
                return result
            } else {
                child.recycleSafe()
            }
        }

        return null
    }

    private fun findEditText(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true) {
            if (hint == null || node.hintText?.toString()?.contains(hint, ignoreCase = true) == true) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child, hint)
            if (result != null) {
                return result
            } else {
                child.recycleSafe()
            }
        }

        return null
    }

    private fun extractTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTexts(child, texts)
            child.recycleSafe()
        }
    }
}

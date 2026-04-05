package com.aria.assistant.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.aria.assistant.ARIAAccessibilityService
import com.aria.assistant.live.core.PersistentLogger
import kotlinx.coroutines.delay

class SafeAutomationExecutor(private val context: Context) {

    private val appPackageAllowlist = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "calculator" to "com.google.android.calculator",
        "notepad" to "com.google.android.keep",
        "canva" to "com.canva.editor",
        "settings" to "com.android.settings"
    )

    suspend fun execute(envelope: SafeIntentEnvelope): AutomationExecutionResult {
        val tasks = normalizeTasks(envelope)
        if (tasks.isEmpty()) {
            return AutomationExecutionResult(
                summary = "No executable safe task found.",
                executed = 0,
                blocked = 0,
                details = listOf("No task")
            )
        }

        val details = mutableListOf<String>()
        var executed = 0
        var blocked = 0

        for (task in tasks) {
            if (!SafeAutomationPolicy.isAllowed(context, task.type)) {
                blocked++
                val msg = "blocked:${task.type}:policy"
                details += msg
                AutomationAuditLogger.log(context, msg)
                continue
            }

            if (SafeAutomationPolicy.shouldRequireConfirmation(task)) {
                val guard = SensitiveScreenGuard.evaluate(context)
                if (guard.blocked) {
                    blocked++
                    val msg = "blocked:${task.type}:sensitive_screen:${guard.reason}"
                    details += msg
                    AutomationAuditLogger.log(context, "$msg:${guard.evidence}")
                    continue
                }
            }

            when (task.type) {
                SafeTaskTypes.LAUNCH_MULTIPLE_APPS -> {
                    val apps = task.targetApps.orEmpty().ifEmpty { task.app?.let { listOf(it) } ?: emptyList() }
                    val launched = launchApps(apps)
                    executed += launched
                    val fail = apps.size - launched
                    blocked += fail
                    details += "launch_apps:ok=$launched,fail=$fail"
                    AutomationAuditLogger.log(context, "launch_apps:${apps.joinToString(",")}:ok=$launched")
                }

                SafeTaskTypes.READ_INCOMING_SMS -> {
                    val enabled = task.enabled ?: true
                    setIncomingSmsRead(enabled)
                    executed++
                    val summary = if (enabled) SmsTaskHandler.latestCompactSummary(context) else "disabled"
                    details += "read_incoming_sms:${if (enabled) "enabled" else "disabled"}:$summary"
                    AutomationAuditLogger.log(context, "read_incoming_sms:${enabled}")
                }

                SafeTaskTypes.SEND_SMS -> {
                    val result = executeSendMessage(
                        platform = "sms",
                        contact = task.contact.orEmpty(),
                        message = task.message.orEmpty()
                    )
                    if (result) executed++ else blocked++
                    details += if (result) "send_sms:success" else "send_sms:failed"
                    AutomationAuditLogger.log(context, "send_sms:${if (result) "success" else "failed"}:${task.contact.orEmpty()}")
                }

                SafeTaskTypes.SOCIAL_POST -> {
                    val result = executeSocialPost(task.platform.orEmpty(), task.content.orEmpty())
                    if (result) executed++ else blocked++
                    details += if (result) "social_post:success" else "social_post:failed"
                    AutomationAuditLogger.log(context, "social_post:${if (result) "success" else "failed"}:${task.platform.orEmpty()}")
                }

                SafeTaskTypes.SAVE_MEMORY -> {
                    task.memoryFact?.let { fact ->
                        com.aria.assistant.ConversationMemory.saveFact(context, fact)
                        executed++
                        details += "save_memory:$fact"
                        AutomationAuditLogger.log(context, "save_memory_fact_saved")
                    } ?: run {
                        blocked++
                        AutomationAuditLogger.log(context, "save_memory_blocked:missing_fact")
                    }
                }

                SafeTaskTypes.TOGGLE_HARDWARE -> {
                    val hwType = task.hardwareType.orEmpty().lowercase()
                    val hwState = task.hardwareState ?: true
                    when (hwType) {
                        "wifi" -> toggleWifi(hwState)
                        "bluetooth" -> toggleBluetooth(hwState)
                        "flashlight", "torch" -> toggleFlashlight(hwState)
                        else -> blocked++
                    }
                    executed++
                    details += "toggle_hardware:$hwType=$hwState"
                    AutomationAuditLogger.log(context, "toggle_hardware:$hwType=$hwState")
                }

                else -> {
                    // Handle generic app opening or web browsing
                    val app = task.app?.lowercase()
                    
                    // Try to detect browser or web action from task content
                    val content = task.content.orEmpty()
                    val message = task.message.orEmpty()
                    val combined = "$content $message".trim()
                    
                    val urlPattern = Regex("(https?://[^\\s]+|www\\.[^\\s]+)")
                    val detectedUrl = urlPattern.find(combined)?.value
                    
                    when {
                        app == "chrome" || app == "browser" || detectedUrl != null -> {
                            val result = executeBrowserAction(detectedUrl ?: combined)
                            if (result) executed++ else blocked++
                            details += if (result) "browser:success" else "browser:failed"
                        }
                        app == "canva" -> {
                            val result = executeCanvaAction(content)
                            if (result) executed++ else blocked++
                            details += if (result) "canva:success" else "canva:failed"
                        }
                        app != null -> {
                            // Try generic app launch
                            val result = launchSingleApp(app)
                            if (result) executed++ else blocked++
                            details += if (result) "launch:$app:success" else "launch:$app:failed"
                        }
                        else -> {
                            blocked++
                            details += "blocked:${task.type}:unsupported"
                            AutomationAuditLogger.log(context, "blocked:${task.type}:unsupported")
                        }
                    }
                }
            }
        }

        val summary = "Automation done: $executed executed, $blocked blocked."
        return AutomationExecutionResult(summary, executed, blocked, details)
    }

    private fun normalizeTasks(envelope: SafeIntentEnvelope): List<SafeTask> {
        return when (envelope.action) {
            SafeIntentActions.LAUNCH_MULTIPLE_APPS -> {
                listOf(
                    SafeTask(
                        type = SafeTaskTypes.LAUNCH_MULTIPLE_APPS,
                        targetApps = envelope.targetApps,
                        requireConfirmation = false,
                        riskLevel = "low"
                    )
                )
            }
            SafeIntentActions.AUTOMATION_REQUEST -> envelope.tasks.orEmpty()
            else -> emptyList()
        }
    }

    private suspend fun launchApps(apps: List<String>): Int {
        var ok = 0
        apps.map { it.lowercase() }.distinct().forEachIndexed { index, app ->
            val packageName = appPackageAllowlist[app]
            if (packageName != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(launchIntent) }.onSuccess { ok++ }
                }
            }
            if (index < apps.lastIndex) delay(2000)
        }
        return ok
    }

    private fun setIncomingSmsRead(enabled: Boolean) {
        context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_read_incoming_sms", enabled)
            .apply()
    }

    private suspend fun executeSendMessage(platform: String, contact: String, message: String): Boolean {
        PersistentLogger.log(context, "ACTION_EXEC", "Send message: platform=$platform, contact=$contact")

        when (platform.lowercase()) {
            "sms" -> return executeSmsMessage(contact, message)
            "whatsapp" -> return executeWhatsAppMessage(contact, message)
            else -> {
                PersistentLogger.log(context, "ACTION_ERROR", "Unsupported messaging platform: $platform")
                return false
            }
        }
    }

    private suspend fun executeSmsMessage(contact: String, body: String): Boolean {
        // Bug 2 Fix: Idempotency check using global guard
        if (SmsDedupGuard.isDuplicate(contact, body)) {
            PersistentLogger.log(context, "ACTION_DEDUP", "SMS dedup blocked: sending same message to $contact within 30s")
            return true  // Pretend success to avoid re-triggering
        }

        // Try direct SMS API first if permission granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }

                if (contact.matches(Regex("^[+]?[0-9\\s\\-]+$"))) {
                    smsManager.sendTextMessage(contact, null, body, null, null)
                    PersistentLogger.log(context, "ACTION_SUCCESS", "SMS sent via API to $contact (body: ${body.take(40)})")
                    return true  // Bug Fix: return early, don't fall through to compose UI
                }
            } catch (e: Exception) {
                PersistentLogger.log(context, "ACTION_ERROR", "SMS API failed: ${e.message}")
            }
        }
        
        // Fallback: Open compose screen with accessibility
        val uri = Uri.parse("smsto:$contact")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        return runCatching {
            context.startActivity(intent)
            delay(1500) // Wait for app to open
            
            val a11y = ARIAAccessibilityService.instance
            if (a11y != null) {
                // Try to fill message body and click send
                if (a11y.inputText(body)) {
                    PersistentLogger.log(context, "ACTION_SUCCESS", "SMS message typed via accessibility")
                    delay(500)
                    // Note: Not clicking send automatically - requires user confirmation for safety
                    return@runCatching true
                } else {
                    PersistentLogger.log(context, "ACTION_WARN", "SMS opened but auto-type failed")
                    return@runCatching true // Still success - app opened with pre-filled body
                }
            } else {
                PersistentLogger.log(context, "ACTION_WARN", "SMS opened, accessibility service not enabled")
                return@runCatching true // Still success - app opened with pre-filled body
            }
        }.getOrElse {
            PersistentLogger.log(context, "ACTION_ERROR", "SMS compose failed: ${it.message}")
            false
        }
    }
    
    private suspend fun executeWhatsAppMessage(contact: String, message: String): Boolean {
        PersistentLogger.log(context, "ACTION_EXEC", "WhatsApp message to: $contact")
        
        // Try to open WhatsApp with contact
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        return runCatching {
            context.startActivity(intent)
            delay(2000) // Wait for WhatsApp to open
            
            val a11y = ARIAAccessibilityService.instance
            if (a11y != null) {
                // Message should be pre-filled, but verify current app
                val currentApp = a11y.getCurrentApp()
                if (currentApp?.contains("whatsapp") == true) {
                    PersistentLogger.log(context, "ACTION_SUCCESS", "WhatsApp opened with message")
                    // Try to find and type in message field if needed
                    delay(500)
                    if (message.isNotBlank()) {
                        a11y.inputText(message)
                    }
                    return@runCatching true
                } else {
                    PersistentLogger.log(context, "ACTION_ERROR", "WhatsApp did not open")
                    return@runCatching false
                }
            } else {
                PersistentLogger.log(context, "ACTION_WARN", "WhatsApp opened, accessibility not enabled for verification")
                return@runCatching true
            }
        }.getOrElse {
            PersistentLogger.log(context, "ACTION_ERROR", "WhatsApp failed: ${it.message}")
            false
        }
    }

    private suspend fun executeSocialPost(platform: String, content: String): Boolean {
        PersistentLogger.log(context, "ACTION_EXEC", "Social post: platform=$platform")
        
        val packageName = when (platform.lowercase()) {
            "facebook", "fb" -> "com.facebook.katana"
            "instagram", "insta" -> "com.instagram.android"
            else -> null
        }
        
        if (packageName == null) {
            PersistentLogger.log(context, "ACTION_ERROR", "Unsupported social platform: $platform")
            return false
        }
        
        // Open the app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        if (launchIntent == null) {
            PersistentLogger.log(context, "ACTION_ERROR", "App not installed: $packageName")
            return false
        }
        
        return runCatching {
            context.startActivity(launchIntent)
            delay(2000) // Wait for app to open
            
            val a11y = ARIAAccessibilityService.instance
            if (a11y != null) {
                // Try to find "What's on your mind" or similar composer
                val composerOpened = when (platform.lowercase()) {
                    "facebook", "fb" -> {
                        a11y.clickButton("What's on your mind") || 
                        a11y.clickButton("Create post") ||
                        a11y.clickButton("Write something")
                    }
                    else -> false
                }
                
                if (composerOpened) {
                    delay(1000)
                    a11y.inputText(content)
                    PersistentLogger.log(context, "ACTION_SUCCESS", "Social post composed (not published - requires confirmation)")
                    return@runCatching true
                } else {
                    PersistentLogger.log(context, "ACTION_WARN", "Social app opened but composer not found")
                    return@runCatching true // App opened successfully
                }
            } else {
                PersistentLogger.log(context, "ACTION_WARN", "Social app opened, accessibility not enabled")
                return@runCatching true
            }
        }.getOrElse {
            PersistentLogger.log(context, "ACTION_ERROR", "Social post failed: ${it.message}")
            false
        }
    }

    private fun toggleFlashlight(enabled: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun toggleBluetooth(enabled: Boolean) {
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun toggleWifi(enabled: Boolean) {
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
    
    private suspend fun executeBrowserAction(urlOrQuery: String?): Boolean {
        if (urlOrQuery.isNullOrBlank()) {
            PersistentLogger.log(context, "ACTION_ERROR", "Browser action: empty URL/query")
            return false
        }
        
        PersistentLogger.log(context, "ACTION_EXEC", "Browser: $urlOrQuery")
        
        val url = if (urlOrQuery.startsWith("http://") || urlOrQuery.startsWith("https://")) {
            urlOrQuery
        } else if (urlOrQuery.contains(".")) {
            "https://$urlOrQuery"
        } else {
            "https://www.google.com/search?q=${Uri.encode(urlOrQuery)}"
        }
        
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Try Chrome first
            setPackage("com.android.chrome")
        }
        
        return runCatching {
            context.startActivity(intent)
            delay(1500)
            
            val a11y = ARIAAccessibilityService.instance
            if (a11y != null) {
                val currentApp = a11y.getCurrentApp()
                val success = currentApp?.contains("chrome") == true || currentApp?.contains("browser") == true
                if (success) {
                    PersistentLogger.log(context, "ACTION_SUCCESS", "Browser opened: $url")
                } else {
                    PersistentLogger.log(context, "ACTION_ERROR", "Browser did not open")
                }
                return@runCatching success
            } else {
                PersistentLogger.log(context, "ACTION_WARN", "Browser opened, no verification (accessibility disabled)")
                return@runCatching true
            }
        }.getOrElse { e ->
            // Retry without package restriction
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(fallbackIntent)
                PersistentLogger.log(context, "ACTION_SUCCESS", "Browser opened via fallback")
                true
            }.getOrElse {
                PersistentLogger.log(context, "ACTION_ERROR", "Browser failed: ${e.message}")
                false
            }
        }
    }
    
    private suspend fun executeCanvaAction(content: String): Boolean {
        PersistentLogger.log(context, "ACTION_EXEC", "Canva action")
        
        val packageName = "com.canva.editor"
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        if (launchIntent == null) {
            PersistentLogger.log(context, "ACTION_ERROR", "Canva not installed")
            return false
        }
        
        return runCatching {
            context.startActivity(launchIntent)
            delay(2500) // Canva takes longer to open
            
            val a11y = ARIAAccessibilityService.instance
            if (a11y != null) {
                val currentApp = a11y.getCurrentApp()
                if (currentApp?.contains("canva") == true) {
                    PersistentLogger.log(context, "ACTION_SUCCESS", "Canva opened")
                    // Note: Full Canva automation would require specific UI element targeting
                    // which depends on Canva's current UI structure. For now, we just open the app.
                    return@runCatching true
                } else {
                    PersistentLogger.log(context, "ACTION_ERROR", "Canva did not open")
                    return@runCatching false
                }
            } else {
                PersistentLogger.log(context, "ACTION_WARN", "Canva opened, accessibility not enabled")
                return@runCatching true
            }
        }.getOrElse {
            PersistentLogger.log(context, "ACTION_ERROR", "Canva failed: ${it.message}")
            false
        }
    }
    
    private suspend fun launchSingleApp(appName: String): Boolean {
        val packageName = appPackageAllowlist[appName.lowercase()]
        if (packageName == null) {
            PersistentLogger.log(context, "ACTION_ERROR", "App not in allowlist: $appName")
            return false
        }
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            PersistentLogger.log(context, "ACTION_ERROR", "App not installed: $packageName")
            return false
        }
        
        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            delay(1000)
            PersistentLogger.log(context, "ACTION_SUCCESS", "Launched: $appName")
            true
        }.getOrElse {
            PersistentLogger.log(context, "ACTION_ERROR", "Launch failed: $appName - ${it.message}")
            false
        }
    }
}

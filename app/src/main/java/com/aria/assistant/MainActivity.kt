package com.aria.assistant

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.aria.assistant.integrations.IntegrationCapabilityManager
import com.aria.assistant.live.LiveTaskManagerActivity
import com.aria.assistant.setup.SetupChecks
import com.aria.assistant.theme.ThemeManager
import com.aria.assistant.theme.ThemePalette
import com.aria.assistant.workspace.WorkspaceRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var setupProgress: LinearProgressIndicator
    private lateinit var setupProgressText: TextView
    private lateinit var permissionsStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var assistantStatus: TextView

    private lateinit var voiceActionButton: MaterialButton
    private lateinit var chatActionButton: MaterialButton
    private lateinit var taskManagerButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var rootAutoEnableButton: MaterialButton
    private lateinit var pushToTalkButton: MaterialButton
    private lateinit var workspaceActionButton: MaterialButton
    private lateinit var terminalActionButton: MaterialButton
    private lateinit var diagnosticsActionButton: MaterialButton
    private lateinit var onboardingOpenButton: MaterialButton
    private lateinit var onboardingSummaryText: TextView

    private lateinit var mascotImage: ImageView
    private lateinit var logoImage: ImageView
    private lateinit var welcomeText: TextView
    private lateinit var rootContainer: View
    private lateinit var themeGallery: LinearLayout

    // Brain Panel
    private lateinit var brainCard: MaterialCardView
    private lateinit var brainStatusText: TextView
    private lateinit var brainMemoryBar: LinearProgressIndicator

    // Recent Alerts
    private lateinit var alertsCard: MaterialCardView
    private lateinit var alertCountText: TextView
    private lateinit var lastAlertText: TextView

    private lateinit var viewModel: MainHomeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainHomeViewModel::class.java]

        // Views
        rootContainer = findViewById(R.id.mainRoot)
        mascotImage = findViewById(R.id.mascotImage)
        logoImage = findViewById(R.id.logoImage)
        welcomeText = findViewById(R.id.welcomeText)
        setupProgress = findViewById(R.id.setupProgress)
        setupProgressText = findViewById(R.id.setupProgressText)
        permissionsStatus = findViewById(R.id.permissionsChecklistStatus)
        overlayStatus = findViewById(R.id.overlayChecklistStatus)
        assistantStatus = findViewById(R.id.assistantChecklistStatus)

        voiceActionButton = findViewById(R.id.voiceActionButton)
        chatActionButton = findViewById(R.id.chatActionButton)
        taskManagerButton = findViewById(R.id.taskManagerButton)
        settingsButton = findViewById(R.id.settingsButton)
        rootAutoEnableButton = findViewById(R.id.rootAutoEnableButton)
        pushToTalkButton = findViewById(R.id.pushToTalkButton)
        workspaceActionButton = findViewById(R.id.workspaceActionButton)
        terminalActionButton = findViewById(R.id.terminalActionButton)
        diagnosticsActionButton = findViewById(R.id.diagnosticsActionButton)
        onboardingOpenButton = findViewById(R.id.onboardingOpenButton)
        onboardingSummaryText = findViewById(R.id.onboardingSummaryText)

        themeGallery = findViewById(R.id.themeGallery)

        brainCard = findViewById(R.id.brainCard)
        brainStatusText = findViewById(R.id.brainStatusText)
        brainMemoryBar = findViewById(R.id.brainMemoryBar)

        alertsCard = findViewById(R.id.alertsCard)
        alertCountText = findViewById(R.id.alertCountText)
        lastAlertText = findViewById(R.id.lastAlertText)

        // ── Button actions ──────────────────────────────────
        voiceActionButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, AssistantActivity::class.java))
        }

        chatActionButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, AssistantActivity::class.java))
        }

        taskManagerButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, LiveTaskManagerActivity::class.java))
        }

        settingsButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        pushToTalkButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(
                Intent(this, AssistantActivity::class.java).apply {
                    putExtra("start_push_to_talk", true)
                }
            )
        }

        workspaceActionButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, WorkspaceManagerActivity::class.java))
        }

        terminalActionButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        diagnosticsActionButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, EnvironmentDiagnosticsActivity::class.java))
        }

        onboardingOpenButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, OnboardingDashboardActivity::class.java))
        }

        // Root Auto-Enable button
        rootAutoEnableButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            runRootAutoEnable()
        }

        // ── Build theme gallery cards ───────────────────────
        buildThemeGallery()
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
        renderSetupState()
        renderOnboardingSnapshot()
        renderBrainPanel()
        renderRecentAlerts()
        viewModel.resumeLogoAnimation()
        applyLogoAnimationState()
    }

    override fun onPause() {
        super.onPause()
        viewModel.pauseLogoAnimation()
        applyLogoAnimationState()
    }

    private fun applyThemeVisuals() {
        runCatching {
            val gradient = ThemeManager.resolveMainGradient(this)
            rootContainer.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                gradient
            )
        }.onFailure {
            Log.e("MainActivity", "applyThemeVisuals failed: ${it.message}")
        }

        runCatching {
            mascotImage.setImageResource(ThemeManager.resolveMascotDrawable(this))
        }.onFailure {
            Log.e("MainActivity", "resolveMascotDrawable failed: ${it.message}")
            mascotImage.setImageResource(R.drawable.ic_mascot_avatar_new)
        }

        val nickname = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
            .getString("nickname", "Commander")
            .orEmpty()
        welcomeText.text = "Welcome back, $nickname ✨"
    }

    private fun renderSetupState() {
        val state = SetupChecks.evaluate(this)
        setupProgress.max = state.totalPermissions
        setupProgress.setProgressCompat(state.grantedPermissions, true)
        setupProgressText.text = "${state.grantedPermissions}/${state.totalPermissions}"

        permissionsStatus.text = buildStatusLine("🔑 Permissions", state.permissionsDone)
        overlayStatus.text = buildStatusLine("🖼️ Display Overlay", state.overlayDone)
        assistantStatus.text = buildStatusLine("📱 Default Assistant", state.assistantDone)

        // Show root auto-enable button only if something is pending
        val needsSetup = !state.permissionsDone || !state.overlayDone || !state.assistantDone
        rootAutoEnableButton.visibility = if (needsSetup) View.VISIBLE else View.GONE
    }

    private fun buildStatusLine(label: String, done: Boolean): String {
        val icon = if (done) "✅" else "❌"
        return "$label  $icon  ${if (done) "Completed" else "Pending"}"
    }

    private fun renderOnboardingSnapshot() {
        val setup = SetupChecks.evaluate(this)
        val capabilityCount = IntegrationCapabilityManager.collect(this)
        val availableCount = capabilityCount.count { it.available }
        val workspaceCount = WorkspaceRegistry.list(this).size

        onboardingSummaryText.text = buildString {
            append("Setup ")
            append(if (setup.allDone) "✅ complete" else "⚠️ needs attention")
            append(" • Integrations ")
            append("$availableCount/${capabilityCount.size}")
            append(" • Workspaces ")
            append(workspaceCount)
        }
    }

    // ── Brain Panel ─────────────────────────────────────────────────
    private fun renderBrainPanel() {
        val prefs = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
        val conversationCount = prefs.getInt("conversation_context_count", 0)
        val modelLang = prefs.getString("v3_offline_model_language", "en").orEmpty()
        val maxTopics = 20

        val displayName = when (modelLang) {
            "en" -> "Vosk English (en-us-0.15)"
            "es" -> "Vosk Spanish (es-0.42)"
            "fr" -> "Vosk French (fr-0.22)"
            "zh" -> "Vosk Chinese (cn-0.22)"
            else -> "Vosk $modelLang"
        }

        brainStatusText.text = "Model: $displayName  •  Memory: $conversationCount / $maxTopics topics"
        brainMemoryBar.max = maxTopics
        brainMemoryBar.setProgressCompat(conversationCount, false)
    }

    // ── Recent Alerts ────────────────────────────────────────────────
    private fun renderRecentAlerts() {
        // Stub: read from a shared prefs counter
        val prefs = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
        val alertCount = prefs.getInt("aria_alert_count", 0)
        val lastAlert = prefs.getString("aria_last_alert", "No recent alerts").orEmpty()

        alertCountText.text = alertCount.toString()
        lastAlertText.text = if (alertCount > 0) lastAlert else "No recent alerts"
    }

    // ── Theme Gallery ────────────────────────────────────────────────
    private fun buildThemeGallery() {
        themeGallery.removeAllViews()
        val labels = ThemeManager.paletteLabels()
        val palettes = ThemeManager.paletteValues()
        val currentPalette = ThemeManager.load(this).palette

        val cardSizePx = dpToPx(76)
        val gapPx = dpToPx(8)

        val accentMap = mapOf(
            ThemePalette.AURORA to 0xFF82A2FF.toInt(),
            ThemePalette.SAKURA to 0xFFF58CCB.toInt(),
            ThemePalette.OCEAN to 0xFF5DD6E8.toInt(),
            ThemePalette.LAVENDER to 0xFFB69CFF.toInt(),
            ThemePalette.MIDNIGHT to 0xFF6E8FFF.toInt(),
            ThemePalette.SUNSET to 0xFFFF6B4A.toInt()
        )

        val gradMap = mapOf(
            ThemePalette.AURORA to intArrayOf(0xFF161B2D.toInt(), 0xFF27375F.toInt()),
            ThemePalette.SAKURA to intArrayOf(0xFF2C1A2F.toInt(), 0xFF5C2F5D.toInt()),
            ThemePalette.OCEAN to intArrayOf(0xFF122831.toInt(), 0xFF1E5066.toInt()),
            ThemePalette.LAVENDER to intArrayOf(0xFF201A30.toInt(), 0xFF3E2F5E.toInt()),
            ThemePalette.MIDNIGHT to intArrayOf(0xFF10152A.toInt(), 0xFF0E1B3F.toInt()),
            ThemePalette.SUNSET to intArrayOf(0xFF1A0A1E.toInt(), 0xFF3D0F2E.toInt())
        )

        palettes.forEachIndexed { index, palette ->
            val accent = accentMap[palette] ?: 0xFF82A2FF.toInt()
            val grads = gradMap[palette] ?: intArrayOf(0xFF161B2D.toInt(), 0xFF27375F.toInt())

            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(cardSizePx, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply {
                        marginEnd = if (index < palettes.size - 1) gapPx else 0
                    }
                radius = dpToPx(14).toFloat()
                strokeWidth = if (palette == currentPalette) dpToPx(3) else 1
                strokeColor = if (palette == currentPalette) accent else 0x30ffffff
                cardElevation = dpToPx(2).toFloat()
                setCardBackgroundColor(grads[0])

                isClickable = true
                isFocusable = true
                foreground = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(12))

                // Color circle
                val circle = View(this@MainActivity).apply {
                    val size = dpToPx(32)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins(0, 0, 0, dpToPx(6))
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accent)
                    }
                }

                addView(circle)

                // Label
                val tv = TextView(this@MainActivity).apply {
                    text = labels[index].replace(Regex("\\p{So} "), "")  // strip emoji
                    textSize = 10f
                    setTextColor(0xFFE0E6F8.toInt())
                    gravity = Gravity.CENTER
                }
                addView(tv)
            }

            card.addView(inner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            card.setOnClickListener {
                val config = ThemeManager.load(this).copy(palette = palette)
                ThemeManager.save(this, config)
                recreate()
            }

            themeGallery.addView(card)
        }
    }

    // ── Logo animation ───────────────────────────────────────────────
    private fun applyLogoAnimationState() {
        val drawable = logoImage.drawable
        if (drawable is AnimatedVectorDrawable) {
            if (viewModel.logoAnimating) drawable.start() else drawable.stop()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    // ── Root Auto-Enable ─────────────────────────────────────
    private fun runRootAutoEnable() {
        rootAutoEnableButton.isEnabled = false
        rootAutoEnableButton.text = "⏳ Enabling..."
        Toast.makeText(this, "⚡ Auto-enabling with root…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failCount = 0

            // 1. Grant all runtime permissions via pm grant
            val perms = listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CONTACTS",
                "android.permission.CALL_PHONE",
                "android.permission.SEND_SMS",
                "android.permission.READ_SMS",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.READ_PHONE_STATE",
                "android.permission.ANSWER_PHONE_CALLS"
            )

            for (perm in perms) {
                if (execRootAndSucceed("pm grant $packageName $perm")) {
                    successCount++
                } else {
                    failCount++
                }
            }

            // 2. Overlay (SYSTEM_ALERT_WINDOW) via appops
            if (execRootAndSucceed("appops set $packageName SYSTEM_ALERT_WINDOW allow")) {
                successCount++
            } else {
                failCount++
            }

            // 3. Set as default assistant
            val componentName = "$packageName/com.aria.assistant.AssistantActivity"
            if (execRootAndSucceed("cmd role add-role-holder android.app.role.ASSISTANT $packageName")) {
                successCount++
            } else {
                // Fallback: settings put secure assistant
                if (execRootAndSucceed("settings put secure assistant $componentName")) {
                    successCount++
                } else {
                    failCount++
                }
            }

            // 4. Enable Accessibility Service via root
            val a11yOk = execRootAndSucceed("settings put secure enabled_accessibility_services $packageName/com.aria.assistant.ARIAAccessibilityService") &&
                execRootAndSucceed("settings put secure accessibility_enabled 1")
            if (a11yOk) {
                successCount++
            } else {
                failCount++
            }

            // 5. Enable Notification Listener via root
            if (execRootAndSucceed("settings put secure enabled_notification_listeners $packageName/com.aria.assistant.AriaNotificationListenerService")) {
                successCount++
            } else if (execRootAndSucceed("cmd notification allow_listener $packageName/com.aria.assistant.AriaNotificationListenerService")) {
                successCount++
            } else {
                failCount++
            }

            val finalSuccess = successCount
            val finalFail = failCount

            withContext(Dispatchers.Main) {
                rootAutoEnableButton.isEnabled = true
                rootAutoEnableButton.text = "⚡ Auto-Enable All (Root)"
                renderSetupState()
                if (finalFail == 0) {
                    Toast.makeText(this@MainActivity, "✅ All setup complete! $finalSuccess items enabled.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "⚠️ $finalSuccess succeeded, $finalFail failed. Some may need manual setup.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun execRoot(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            exitCode to (output + error).trim()
        } catch (e: Exception) {
            -1 to e.message.orEmpty()
        }
    }

    private fun execRootAndSucceed(command: String): Boolean {
        val (code, _) = execRoot(command)
        return code == 0
    }
}

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
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.aria.assistant.live.LiveTaskManagerActivity
import com.aria.assistant.setup.SetupChecks
import com.aria.assistant.theme.ThemeManager
import com.aria.assistant.theme.ThemePalette
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
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

        // ── Build theme gallery cards ───────────────────────
        buildThemeGallery()
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
        renderSetupState()
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
    }

    private fun buildStatusLine(label: String, done: Boolean): String {
        val icon = if (done) "✅" else "❌"
        return "$label  $icon  ${if (done) "Completed" else "Pending"}"
    }

    // ── Brain Panel ─────────────────────────────────────────────────
    private fun renderBrainPanel() {
        val prefs = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
        val conversationCount = prefs.getInt("conversation_context_count", 0)
        val modelLang = prefs.getString("v3_offline_model_language", "bn").orEmpty()
        val maxTopics = 20

        val displayName = when (modelLang) {
            "bn" -> "Vosk Bangla (bn-0.4)"
            "en" -> "Vosk English (en-us-0.22)"
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
}

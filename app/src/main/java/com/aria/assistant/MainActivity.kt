package com.aria.assistant

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.aria.assistant.live.LiveTaskManagerActivity
import com.aria.assistant.setup.SetupChecks
import com.aria.assistant.theme.ThemeManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var setupProgress: LinearProgressIndicator
    private lateinit var setupProgressText: TextView

    private lateinit var permissionsStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var assistantStatus: TextView

    private lateinit var openSetupButton: MaterialButton
    private lateinit var startAriaButton: MaterialButton
    private lateinit var taskManagerButton: MaterialButton
    private lateinit var settingsButton: MaterialButton

    private lateinit var rootContainer: View
    private lateinit var mascotImage: ImageView
    private lateinit var logoImage: ImageView
    private lateinit var welcomeText: TextView

    private lateinit var viewModel: MainHomeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainHomeViewModel::class.java]

        rootContainer = findViewById(R.id.mainRoot)
        mascotImage = findViewById(R.id.mascotImage)
        logoImage = findViewById(R.id.logoImage)
        welcomeText = findViewById(R.id.welcomeTitle)

        setupProgress = findViewById(R.id.setupProgress)
        setupProgressText = findViewById(R.id.setupProgressText)

        permissionsStatus = findViewById(R.id.permissionsChecklistStatus)
        overlayStatus = findViewById(R.id.overlayChecklistStatus)
        assistantStatus = findViewById(R.id.assistantChecklistStatus)

        openSetupButton = findViewById(R.id.openSetupButton)
        startAriaButton = findViewById(R.id.startAriaButton)
        taskManagerButton = findViewById(R.id.taskManagerButton)
        settingsButton = findViewById(R.id.settingsButton)

        openSetupButton.setOnClickListener {
            viewModel.pauseLogoAnimation()
            startActivity(Intent(this, SetupActivity::class.java))
        }

        startAriaButton.setOnClickListener {
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
    }

    override fun onResume() {
        super.onResume()
        applyThemeVisuals()
        renderSetupState()
        viewModel.resumeLogoAnimation()
        applyLogoAnimationState()
    }

    override fun onPause() {
        super.onPause()
        viewModel.pauseLogoAnimation()
        applyLogoAnimationState()
    }

    private fun applyThemeVisuals() {
        val gradient = ThemeManager.resolveMainGradient(this)
        rootContainer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradient
        )

        mascotImage.setImageResource(ThemeManager.resolveMascotDrawable(this))

        val nickname = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
            .getString("nickname", "Commander")
            .orEmpty()
        welcomeText.text = "Welcome back, $nickname ✨"
    }

    private fun renderSetupState() {
        val state = SetupChecks.evaluate(this)

        setupProgress.max = state.totalPermissions
        setupProgress.setProgressCompat(state.grantedPermissions, true)
        setupProgressText.text = "${state.grantedPermissions}/${state.totalPermissions} permissions"

        permissionsStatus.text = "Permissions: ${if (state.permissionsDone) "✅ Completed" else "❌ Pending"}"
        overlayStatus.text = "Display Overlay: ${if (state.overlayDone) "✅ Completed" else "❌ Pending"}"
        assistantStatus.text = "Default Assistant: ${if (state.assistantDone) "✅ Completed" else "❌ Pending"}"

        startAriaButton.isEnabled = state.allDone
        startAriaButton.alpha = if (state.allDone) 1f else 0.6f
        if (state.allDone) {
            startAriaButton.animate()
                .scaleX(1.03f)
                .scaleY(1.03f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator())
                .withEndAction {
                    startAriaButton.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
                .start()
        }
    }

    private fun applyLogoAnimationState() {
        val drawable = logoImage.drawable
        if (drawable is AnimatedVectorDrawable) {
            if (viewModel.logoAnimating) drawable.start() else drawable.stop()
        }
    }
}

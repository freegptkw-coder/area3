package com.aria.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.aria.assistant.live.core.LiveEventBus
import com.aria.assistant.live.core.VoiceSessionEvent
import com.aria.assistant.live.core.VoiceSessionState
import kotlinx.coroutines.flow.collect
import com.aria.assistant.live.core.StreamingSttGateway
import com.aria.assistant.live.core.SttTranscriptEvent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.automation.AutomationAuditLogger
import com.aria.assistant.automation.ParsedAutomationCommand
import com.aria.assistant.automation.SafeIntentEnvelope
import com.aria.assistant.live.LiveTaskManagerActivity
import com.aria.assistant.multitask.AriaTaskRuntime
import com.aria.assistant.multitask.AriaTaskTypes
import com.aria.assistant.multitask.TaskPriority
import com.aria.assistant.multitask.TaskRequest
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class AssistantActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var voiceButton: MaterialButton
    private lateinit var stopSpeakButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var taskManagerButton: MaterialButton
    private lateinit var partialResultText: TextView
    
    private var sttGateway: StreamingSttGateway? = null
    private var isLocalSttListening = false
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val speechQueue: ArrayDeque<String> = ArrayDeque()
    private var isSpeakingNow = false
    private var currentSpeakJob: Job? = null
    private val healthHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AppHealthMonitor.markAlive(this@AssistantActivity)
            healthHandler.postDelayed(this, 60_000)
        }
    }
    
    private lateinit var lettaService: LettaApiService
    private val taskOrchestrator = AriaTaskRuntime.orchestrator
    private var pendingConfirmationEnvelope: SafeIntentEnvelope? = null
    
    private val RECORD_AUDIO_PERMISSION = 100
    private var recognitionRetryCount = 0
    private val maxRecognitionRetry = 2
    private var manualMicTriggerUntilMs: Long = 0L
    
    private var liveEventJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        
        lettaService = LettaApiService(this)
        AppHealthMonitor.installCrashHandler(applicationContext)
        AppHealthMonitor.markAppStart(this)
        
        // Initialize views
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        chatRecyclerView.adapter = chatAdapter
        
        messageInput = findViewById(R.id.messageInput)
        voiceButton = findViewById(R.id.voiceButton)
        stopSpeakButton = findViewById(R.id.stopSpeakButton)
        sendButton = findViewById(R.id.sendButton)
        settingsButton = findViewById(R.id.settingsButton)
        taskManagerButton = findViewById(R.id.taskManagerButton)
        partialResultText = findViewById(R.id.partialResultText)
        val voiceStatusText: TextView = findViewById(R.id.voiceStatusText)

        AppHealthMonitor.consumeLastCrashSummary(this)?.let {
            addSystemMessage("Recovered from previous crash: $it")
        }
        
        // Initialize TTS
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { runOnUiThread { onSpeechFinished() } }
            override fun onError(utteranceId: String?) { runOnUiThread { onSpeechFinished() } }
        })
        
        // Initialize Streaming STT Gateway for Gemini-like continuous listening
        sttGateway = StreamingSttGateway.createDefault(this) { event ->
            runOnUiThread { handleLocalSttEvent(event) }
        }
        
        // Check permissions
        checkPermissions()
        
        // Listen to LiveEventBus for true duplex Gemini-like frontend
        liveEventJob = CoroutineScope(Dispatchers.Main).launch {
            launch {
                LiveEventBus.events.collect { event ->
                    handleLiveEvent(event)
                }
            }
            launch {
                LiveEventBus.state.collect { state ->
                    handleLiveState(state)
                }
            }
        }
        
        // Set up button listeners
        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotEmpty()) {
                sendMessage(message)
                messageInput.text.clear()
            }
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        taskManagerButton.setOnClickListener {
            startActivity(Intent(this, LiveTaskManagerActivity::class.java))
        }
        
        voiceButton.setOnClickListener {
            manualMicTriggerUntilMs = System.currentTimeMillis() + 20_000L
            val liveEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
                .getBoolean("live_mode_enabled", false)
            if (liveEnabled) {
                // Tell the background service to start listening
                CoroutineScope(Dispatchers.IO).launch {
                    LiveEventBus.commands.emit("start_mic")
                }
            } else {
                startVoiceRecognition()
            }
        }

        stopSpeakButton.setOnClickListener {
            val liveEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
                .getBoolean("live_mode_enabled", false)
            if (liveEnabled) {
                CoroutineScope(Dispatchers.IO).launch {
                    LiveEventBus.commands.emit("stop_speak")
                }
            } else {
                stopCurrentSpeech()
            }
        }
        
        // Welcome message
        addAssistantMessage("Hello! I'm ARIA Assistant. How can I help you?")

        // Health heartbeat
        healthHandler.postDelayed(heartbeatRunnable, 60_000)
    }
    
    private fun handleLiveEvent(event: VoiceSessionEvent) {
        when (event) {
            is VoiceSessionEvent.SttPartial -> {
                partialResultText.visibility = android.view.View.VISIBLE
                partialResultText.text = event.text
            }
            is VoiceSessionEvent.SttFinal -> {
                partialResultText.visibility = android.view.View.GONE
                addUserMessage(event.text)
            }
            is VoiceSessionEvent.AssistantAudioStarted -> {
                voiceButton.visibility = android.view.View.GONE
                stopSpeakButton.visibility = android.view.View.VISIBLE
            }
            VoiceSessionEvent.AssistantAudioFinished -> {
                stopSpeakButton.visibility = android.view.View.GONE
                voiceButton.visibility = android.view.View.VISIBLE
            }
            is VoiceSessionEvent.LlmResponseChunk -> {
                if (currentLiveMessageIndex == -1) {
                    if (chatAdapter.getItemCountCurrent() > 0) {
                        chatAdapter.removeLastMessage() // Remove any generic processing indicator
                    }
                    chatAdapter.addMessage(ChatMessage(text = event.text, sender = ChatMessage.SenderType.ASSISTANT))
                    currentLiveMessageIndex = chatAdapter.getItemCountCurrent() - 1
                } else {
                    chatAdapter.appendChunkToLastMessage(event.text)
                    scrollToBottom()
                }
            }
            VoiceSessionEvent.SessionStopped -> {
                currentLiveMessageIndex = -1
            }
            is VoiceSessionEvent.LlmRequestStarted -> {
                currentLiveMessageIndex = -1
                addAssistantMessage("Thinking...")
            }
            else -> {}
        }
    }

    private var currentLiveMessageIndex = -1

    private fun handleLiveState(state: VoiceSessionState) {
        when (state) {
            VoiceSessionState.LISTENING -> {
                voiceButton.text = "🔴"
                setVoiceStatus("🎧 Listening...")
            }
            VoiceSessionState.SPEAKING -> {
                setVoiceStatus("🤖 Speaking...")
            }
            VoiceSessionState.THINKING -> {
                setVoiceStatus("🧠 Thinking...")
            }
            VoiceSessionState.IDLE -> {
                voiceButton.text = "🎤"
                setVoiceStatus("🔇 Idle")
            }
            else -> {
                setVoiceStatus("⏱️ ${state.name}")
            }
        }
    }
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION
            )
        }
    }
    
    private fun handleLocalSttEvent(event: SttTranscriptEvent) {
        when (event) {
            SttTranscriptEvent.ListeningStarted -> {
                voiceButton.text = "🔴"
                setVoiceStatus("🎧 Listening...")
                partialResultText.text = "Listening to your voice..."
                partialResultText.visibility = android.view.View.VISIBLE
                isLocalSttListening = true
            }
            SttTranscriptEvent.ListeningStopped -> {
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
                isLocalSttListening = false
                if (!ttsReady) setVoiceStatus("🔇 Idle")
            }
            is SttTranscriptEvent.Partial -> {
                partialResultText.text = event.text
                partialResultText.visibility = android.view.View.VISIBLE
            }
            is SttTranscriptEvent.Final -> {
                val matches = event.text
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
                if (matches.isNotEmpty()) {
                    val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
                    val wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false)
                    val manualTriggered = System.currentTimeMillis() <= manualMicTriggerUntilMs

                    var finalText = matches
                    if (wakeWordEnabled && !manualTriggered) {
                        val lower = matches.lowercase(Locale.getDefault())
                        val hasWakeWord = lower.contains("hey aria") || lower.contains("hi aria") || lower.startsWith("aria")
                        if (!hasWakeWord) {
                            Toast.makeText(this@AssistantActivity, "Wake word on: bolo 'Hey ARIA'", Toast.LENGTH_SHORT).show()
                            return
                        }
                        finalText = matches
                            .replace(Regex("(?i)^\\s*(hey|hi)?\\s*aria[,! ]*"), "")
                            .trim()
                        if (finalText.isBlank()) {
                            Toast.makeText(this@AssistantActivity, "Bolo: Hey ARIA, then command", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }

                    if (manualTriggered && wakeWordEnabled) {
                        addSystemMessage("Manual mic mode active: wake word bypassed")
                    }

                    messageInput.setText(finalText)
                    sendMessage(finalText)
                }
            }
            is SttTranscriptEvent.Error -> {
                val reason = event.reason
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
                setVoiceStatus("⚠️ Mic error: $reason")
                Toast.makeText(this@AssistantActivity, "Voice error: $reason", Toast.LENGTH_SHORT).show()
                if (event.code == 9) { // ERROR_INSUFFICIENT_PERMISSIONS
                    checkPermissions()
                }
            }
            SttTranscriptEvent.Timeout -> {
                // Ignore timeout visually, gateway handles restart if needed
            }
            SttTranscriptEvent.Unavailable -> {
                setVoiceStatus("⚠️ Mic unavailable")
                Toast.makeText(this@AssistantActivity, "Voice recognition unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setVoiceStatus("🎙️ Mic permission needed")
            checkPermissions()
            return
        }

        val liveEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .getBoolean("live_mode_enabled", false)
        if (liveEnabled) {
            CoroutineScope(Dispatchers.IO).launch {
                LiveEventBus.commands.emit("start_mic")
            }
        } else {
            if (isLocalSttListening) {
                sttGateway?.stop()
            } else {
                setVoiceStatus("🎧 Starting mic...")
                sttGateway?.start()
                // Fake trigger VAD so it doesn't wait
                sttGateway?.onVoiceActivity(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            setVoiceStatus("🎙️ Mic permission required")
            addSystemMessage("Mic permission off. Please allow microphone from permission popup/settings.")
        }

        val liveEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .getBoolean("live_mode_enabled", false)
        val sessionActive = com.aria.assistant.live.ConsentStore.isSessionActive(this)
        if (liveEnabled && sessionActive) {
            com.aria.assistant.live.LiveModeController.startService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        val liveEnabled = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .getBoolean("live_mode_enabled", false)
        val sessionActive = com.aria.assistant.live.ConsentStore.isSessionActive(this)
        if (liveEnabled && sessionActive) {
            com.aria.assistant.live.LiveModeController.startService(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setVoiceStatus("✅ Mic permission granted")
                startVoiceRecognition()
            } else {
                setVoiceStatus("❌ Mic permission denied")
                Toast.makeText(this, "Mic permission chara voice command kaj korbe na", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendMessage(message: String) {
        // Add user message to chat
        addUserMessage(message)

        val normalized = message.trim().lowercase(Locale.getDefault())

        pendingConfirmationEnvelope?.let { pending ->
            when {
                isConfirmMessage(normalized) -> {
                    pendingConfirmationEnvelope = null
                    val ack = "Dhonnobad, sensitive task confirm peyechi. Ekhon safe automation execute kortesi."
                    addAssistantMessage(ack)
                    enqueueSpeech(ack)
                    executeSafeIntent(pending)
                    return
                }
                isCancelMessage(normalized) -> {
                    pendingConfirmationEnvelope = null
                    val ack = "Thik ache, sensitive task cancel kore dilam ✅"
                    addAssistantMessage(ack)
                    enqueueSpeech(ack)
                    return
                }
                else -> {
                    val ack = "Ager sensitive request pending ache. Bolun 'confirm' or 'cancel'."
                    addAssistantMessage(ack)
                    enqueueSpeech(ack)
                    return
                }
            }
        }
        
        // Safe local automation parse first (multi-task supported)
        val parsedLocal = VoiceCommandParser.parseAutomation(message)
        if (parsedLocal != null) {
            handleParsedAutomation(parsedLocal)
            return
        }
        
        // Show processing indicator
        addAssistantMessage("Processing...")
        
        taskOrchestrator.scheduleTask(
            TaskRequest(
                title = "Answer: ${message.take(28)}",
                type = AriaTaskTypes.CHAT_QUERY,
                priority = TaskPriority.NORMAL,
                description = "AI response in progress"
            )
        ) {
            updateProgress(15, "Thinking...")

            var currentMessageIndex = -1
            val responseBuilder = java.lang.StringBuilder()

            val response = lettaService.streamMessage(message, liveShortResponse = false) { chunk ->
                runOnUiThread {
                    if (currentMessageIndex == -1) {
                        if (chatAdapter.getItemCountCurrent() > 0) {
                            chatAdapter.removeLastMessage() // Remove "Processing..."
                        }
                        chatAdapter.addMessage(ChatMessage(text = chunk, sender = ChatMessage.SenderType.ASSISTANT))
                        currentMessageIndex = chatAdapter.getItemCountCurrent() - 1
                    } else {
                        chatAdapter.appendChunkToLastMessage(chunk)
                        scrollToBottom()
                    }
                }
            }

            updateProgress(70, "Response finished")

            withContext(Dispatchers.Main) {
                if (currentMessageIndex == -1) {
                    if (chatAdapter.getItemCountCurrent() > 0) {
                        chatAdapter.removeLastMessage() // Remove "Processing..."
                    }
                    addAssistantMessage(response.text)
                }

                val parsedAssistant = VoiceCommandParser.parseAssistantJson(response.text)
                if (parsedAssistant != null) {
                    handleParsedAutomation(parsedAssistant)
                } else {
                    enqueueSpeech(response.text)
                }

                response.rootCommand?.takeIf { it.isNotBlank() }?.let { legacy ->
                    addSystemMessage("⛔ Raw root command ignored by Safe Automation policy")
                    AutomationAuditLogger.log(this@AssistantActivity, "root_command_ignored:${legacy.take(120)}")
                }
            }
            updateProgress(100, "Delivered")
        }
    }

    private fun handleParsedAutomation(parsed: ParsedAutomationCommand) {
        val ack = parsed.acknowledgement.ifBlank { "Thik ache, safe automation request receive hoyeche." }
        val safeJson = VoiceCommandParser.toJson(parsed.envelope)

        addAssistantMessage(ack)
        enqueueSpeech(ack)
        addSystemMessage("Safe Intent JSON: $safeJson")

        val hasExecutableTask = parsed.envelope.action == "launch_multiple_apps" ||
            !parsed.envelope.tasks.isNullOrEmpty()
        if (!hasExecutableTask) {
            addSystemMessage("No executable task requested. Staying on standby.")
            return
        }

        if (VoiceCommandParser.requiresConfirmation(parsed.envelope)) {
            pendingConfirmationEnvelope = parsed.envelope
            val confirmPrompt = "Sensitive task detect hoyeche. Bolun 'confirm' to proceed or 'cancel'."
            addSystemMessage(confirmPrompt)
            enqueueSpeech("Sensitive action ache. Confirm bolle age barabo.")
            return
        }

        executeSafeIntent(parsed.envelope)
    }

    private fun executeSafeIntent(envelope: SafeIntentEnvelope) {
        taskOrchestrator.scheduleTask(
            TaskRequest(
                title = "Automation request",
                type = AriaTaskTypes.AUTOMATION,
                priority = TaskPriority.HIGH,
                description = "Executing safe automation"
            )
        ) {
            updateProgress(20, "Validating policy")
            val result = VoiceCommandParser.executeAutomation(this@AssistantActivity, envelope)
            updateProgress(85, "Publishing result")

            withContext(Dispatchers.Main) {
                addSystemMessage(result.summary)
                if (result.details.isNotEmpty()) {
                    addSystemMessage(result.details.joinToString(" | "))
                }
            }
            updateProgress(100, "Done")
        }
    }

    private fun isConfirmMessage(text: String): Boolean {
        val keywords = listOf("confirm", "yes", "ok", "ha", "hmm yes", "proceed", "dao")
        return keywords.any { text == it || text.startsWith("$it ") }
    }

    private fun isCancelMessage(text: String): Boolean {
        val keywords = listOf("cancel", "no", "stop", "na", "bad dao")
        return keywords.any { text == it || text.startsWith("$it ") }
    }
    
    private fun addUserMessage(text: String) {
        chatAdapter.addMessage(ChatMessage(text = text, sender = ChatMessage.SenderType.USER))
        scrollToBottom()
    }
    
    private fun addAssistantMessage(text: String) {
        chatAdapter.addMessage(ChatMessage(text = text, sender = ChatMessage.SenderType.ASSISTANT))
        scrollToBottom()
    }
    
    private fun addSystemMessage(text: String) {
        chatAdapter.addMessage(ChatMessage(text = text, sender = ChatMessage.SenderType.SYSTEM))
        scrollToBottom()
    }
    
    private fun scrollToBottom() {
        if (chatAdapter.getItemCountCurrent() > 0) {
            chatRecyclerView.smoothScrollToPosition(chatAdapter.getItemCountCurrent() - 1)
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts.language = Locale.US
        }
    }
    


    private fun enqueueSpeech(text: String) {
        if (text.isBlank()) return
        speechQueue.addLast(text)
        if (!isSpeakingNow) processSpeechQueue()
    }

    private fun processSpeechQueue() {
        if (isSpeakingNow || speechQueue.isEmpty()) return
        val next = speechQueue.removeFirst()
        isSpeakingNow = true
        
        runOnUiThread {
            voiceButton.visibility = android.view.View.GONE
            stopSpeakButton.visibility = android.view.View.VISIBLE
        }
        
        speakWithVoiceProvider(next)
    }

    private fun onSpeechFinished() {
        isSpeakingNow = false
        setVoiceStatus("🔇 Idle")
        
        runOnUiThread {
            if (speechQueue.isEmpty()) {
                stopSpeakButton.visibility = android.view.View.GONE
                voiceButton.visibility = android.view.View.VISIBLE
            }
        }
        
        processSpeechQueue()
    }

    private fun setVoiceStatus(status: String) {
        val voiceStatusText: TextView? = findViewById(R.id.voiceStatusText)
        voiceStatusText?.text = status
    }

    private fun canUseElevenLabs(charCount: Int): Boolean {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        val savedMonth = prefs.getString("elevenlabs_month", monthKey)
        var used = prefs.getInt("elevenlabs_chars_used", 0)

        if (savedMonth != monthKey) {
            used = 0
            prefs.edit().putString("elevenlabs_month", monthKey).putInt("elevenlabs_chars_used", 0).apply()
        }

        return used + charCount <= 9800
    }

    private fun addElevenLabsUsage(charCount: Int) {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val used = prefs.getInt("elevenlabs_chars_used", 0)
        prefs.edit().putInt("elevenlabs_chars_used", used + charCount).apply()
    }

    private fun stopCurrentSpeech() {
        currentSpeakJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        if (ttsReady) tts.stop()
        speechQueue.clear()
        isSpeakingNow = false
        
        runOnUiThread {
            stopSpeakButton.visibility = android.view.View.GONE
            voiceButton.visibility = android.view.View.VISIBLE
        }
        
        setVoiceStatus("⏹ Stopped")
    }

    private fun speakWithVoiceProvider(text: String) {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val ttsProvider = prefs.getString("tts_provider", "android") ?: "android"
        val voiceId = prefs.getString("voice_id", "android_default") ?: "android_default"
        val voiceApiKey = SecurePrefs.getDecryptedString(this, "ARIA_PREFS", "voice_api_key_enc", "voice_api_key")

        when (ttsProvider) {
            "android" -> {
                setVoiceStatus("🤖 Android speaking...")
                if (ttsReady) {
                    val utteranceId = "aria_${System.currentTimeMillis()}"
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } else {
                    onSpeechFinished()
                }
            }
            "elevenlabs", "cartesia" -> {
                if (voiceApiKey.isEmpty()) {
                    setVoiceStatus("⚠️ Voice key missing: invalid_key")
                    addSystemMessage("TTS fallback reason: invalid_key")
                    fallbackToAndroidTts(text)
                    return
                }

                if (ttsProvider == "elevenlabs" && !canUseElevenLabs(text.length)) {
                    setVoiceStatus("⚠️ ElevenLabs limit reached: quota_exceeded")
                    addSystemMessage("TTS fallback reason: quota_exceeded")
                    fallbackToAndroidTts(text)
                    return
                }

                setVoiceStatus("🎙️ Generating voice...")
                currentSpeakJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val provider = if (ttsProvider == "elevenlabs") TTSProvider.ELEVENLABS else TTSProvider.CARTESIA
                        var audioFile: File? = null

                        for (attempt in 1..2) {
                            audioFile = TTSProviders.generateSpeech(this@AssistantActivity, text, provider, voiceId, voiceApiKey)
                            if (audioFile != null && audioFile.exists()) break
                            if (attempt < 2) delay(700)
                        }

                        if (audioFile != null && audioFile.exists()) {
                            if (ttsProvider == "elevenlabs") addElevenLabsUsage(text.length)
                            prefs.edit()
                                .putString("last_good_voice_provider", ttsProvider)
                                .putString("last_good_voice_id", voiceId)
                                .apply()
                            withContext(Dispatchers.Main) {
                                setVoiceStatus("🔊 Playing premium voice...")
                                playAudioFile(audioFile, text)
                            }
                        } else {
                            val reason = TTSProviders.getLastErrorReason().ifBlank { "unknown_error" }
                            val fallbackVoiceProvider = prefs.getString("last_good_voice_provider", "") ?: ""
                            val fallbackVoiceId = prefs.getString("last_good_voice_id", "") ?: ""

                            val recoveredFile = if (
                                fallbackVoiceProvider == ttsProvider &&
                                fallbackVoiceId.isNotBlank() &&
                                fallbackVoiceId != voiceId
                            ) {
                                TTSProviders.generateSpeech(this@AssistantActivity, text, provider, fallbackVoiceId, voiceApiKey)
                            } else {
                                null
                            }

                            withContext(Dispatchers.Main) {
                                if (recoveredFile != null && recoveredFile.exists()) {
                                    setVoiceStatus("✅ Recovered using last stable voice")
                                    playAudioFile(recoveredFile, text)
                                } else {
                                    setVoiceStatus("⚠️ Premium failed: $reason")
                                    addSystemMessage("TTS fallback reason: $reason")
                                    fallbackToAndroidTts(text)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setVoiceStatus("⚠️ Voice error: ${e.message ?: "unknown"}")
                            fallbackToAndroidTts(text)
                        }
                    }
                }
            }
            else -> {
                setVoiceStatus("🤖 Android speaking...")
                if (ttsReady) {
                    val utteranceId = "aria_${System.currentTimeMillis()}"
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } else {
                    onSpeechFinished()
                }
            }
        }
    }

    private fun fallbackToAndroidTts(text: String) {
        if (ttsReady) {
            val utteranceId = "aria_${System.currentTimeMillis()}"
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onSpeechFinished()
        }
    }

    private fun playAudioFile(audioFile: File, fallbackText: String) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    audioFile.delete()
                    onSpeechFinished()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    mediaPlayer = null
                    audioFile.delete()
                    if (ttsReady) {
                        val utteranceId = "aria_${System.currentTimeMillis()}"
                        tts.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    } else {
                        onSpeechFinished()
                    }
                    true
                }
            }
        } catch (_: Exception) {
            audioFile.delete()
            if (ttsReady) {
                val utteranceId = "aria_${System.currentTimeMillis()}"
                tts.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                onSpeechFinished()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        healthHandler.removeCallbacks(heartbeatRunnable)
        AppHealthMonitor.markCleanExit(this)
        currentSpeakJob?.cancel()
        mediaPlayer?.release()
        tts.stop()
        tts.shutdown()
        sttGateway?.stop()
    }
}

package com.aria.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    
    private lateinit var speechRecognizer: SpeechRecognizer
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
        
        // Initialize Speech Recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        setupSpeechRecognizer()
        
        // Check permissions
        checkPermissions()
        
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
            startVoiceRecognition()
        }

        stopSpeakButton.setOnClickListener {
            stopCurrentSpeech()
        }
        
        // Welcome message
        addAssistantMessage("Hello! I'm ARIA Assistant. How can I help you?")

        // Health heartbeat
        healthHandler.postDelayed(heartbeatRunnable, 60_000)
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
    
    private fun setupSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceButton.text = "🔴"
                setVoiceStatus("🎧 Listening...")
                partialResultText.text = "Listening to your voice..."
                partialResultText.visibility = android.view.View.VISIBLE
            }
            
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
            }
            
            override fun onError(error: Int) {
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
                val reason = speechErrorReason(error)
                setVoiceStatus("⚠️ Mic error $error: $reason")
                Toast.makeText(this@AssistantActivity, "Voice error $error: $reason", Toast.LENGTH_SHORT).show()

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    checkPermissions()
                    return
                }

                if (shouldRetryRecognition(error) && recognitionRetryCount < maxRecognitionRetry) {
                    recognitionRetryCount++
                    recreateSpeechRecognizer()
                    Handler(Looper.getMainLooper()).postDelayed({
                        startVoiceRecognition()
                    }, 650)
                } else {
                    recognitionRetryCount = 0
                }
            }
            
            override fun onResults(results: Bundle?) {
                voiceButton.text = "🎤"
                partialResultText.visibility = android.view.View.GONE
                recognitionRetryCount = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val rawText = matches[0]
                    val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
                    val wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false)
                    val manualTriggered = System.currentTimeMillis() <= manualMicTriggerUntilMs

                    var finalText = rawText
                    if (wakeWordEnabled && !manualTriggered) {
                        val lower = rawText.lowercase(Locale.getDefault())
                        val hasWakeWord = lower.contains("hey aria") || lower.contains("hi aria") || lower.startsWith("aria")
                        if (!hasWakeWord) {
                            Toast.makeText(this@AssistantActivity, "Wake word on: bolo 'Hey ARIA'", Toast.LENGTH_SHORT).show()
                            return
                        }
                        finalText = rawText
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
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val rawText = matches[0]
                    partialResultText.text = rawText
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    
    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setVoiceStatus("🎙️ Mic permission needed")
            checkPermissions()
            return
        }

        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val selectedLang = prefs.getString("speech_recognition_lang", "auto") ?: "auto"
        val languageCode = when (selectedLang) {
            "bn-BD" -> "bn-BD"
            "en-US" -> "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        }

        try {
            setVoiceStatus("🎧 Starting mic...")
            speechRecognizer.startListening(intent)
        } catch (_: Exception) {
            recreateSpeechRecognizer()
            Toast.makeText(this, "Mic service restarted, try again", Toast.LENGTH_SHORT).show()
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

    private fun shouldRetryRecognition(error: Int): Boolean {
        return error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }

    private fun speechErrorReason(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio input problem"
            SpeechRecognizer.ERROR_CLIENT -> "client busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "network issue"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "could not understand"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
            else -> "unknown"
        }
    }

    private fun recreateSpeechRecognizer() {
        try {
            speechRecognizer.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        setupSpeechRecognizer()
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
        speechRecognizer.destroy()
    }
}

package com.jarvis.one

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val scope = MainScope()
    private lateinit var messagesView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var liveButton: Button
    private lateinit var tts: TextToSpeech
    private var chatId: String = ""
    private var pendingCameraAction: String? = null
    private var sendJob: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var stopRequested = false
    private var sending = false
    private var pendingLiveStart = false
    private var liveModeActive = false
    private var liveListening = false
    private var liveAwaitingResponse = false
    private var liveSpeechPending = false
    private var liveRecognizer: SpeechRecognizer? = null
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val restartLiveListening = Runnable { startLiveListening() }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePickedImage(uri)
    }
    private val cameraPicker = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            handleImageBytes(stream.toByteArray(), "image/jpeg")
        }
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingLiveStart) startLiveSession() else listen()
        } else {
            pendingLiveStart = false
            toast("Microphone permission is required for voice input.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppPrefs.bool(this, "light_theme")) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        window.statusBarColor = AppPrefs.backgroundColor(this)
        window.navigationBarColor = AppPrefs.backgroundColor(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (AppPrefs.bool(this, "light_theme")) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        chatId = AppPrefs.get(this, "current_chat_id").ifBlank {
            UUID.randomUUID().toString().also { AppPrefs.put(this, "current_chat_id", it) }
        }
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == LIVE_UTTERANCE_ID) {
                    runOnUiThread { onLiveSpeechFinished() }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == LIVE_UTTERANCE_ID) {
                    runOnUiThread { onLiveSpeechFinished() }
                }
            }
        })
        buildUi()
        loadMessages()
        requestNeededPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_SPEAK_TEXT)?.let { speak(it) }
        intent.getStringExtra(EXTRA_SCREEN_TEXT)?.let { screen ->
            scope.launch { sendMessage("What is on my screen? Answer based on the visible content.", "Visible screen text:\n$screen") }
        }
        intent.getStringExtra(EXTRA_HOTWORD_TEXT)?.takeIf { it.isNotBlank() }?.let { command ->
            scope.launch { sendMessage(command) }
        }
        intent.getStringExtra(EXTRA_GAME_ACTION)?.let { action ->
            try {
                ActionDispatcher.execute(this, org.json.JSONObject(action)) { toast(it) }
            } catch (error: Exception) {
                toast(error.message ?: "Could not start the game agent.")
            }
        }
        intent.getStringExtra(EXTRA_CAMERA_ACTION)?.let { requestCameraAction(it) }
        if (intent.getBooleanExtra(EXTRA_SHOW_NOTES, false)) showNotes()
        if (intent.getBooleanExtra(EXTRA_OPEN_PANEL, false)) {
            if (JarvisAccessibilityService.instance != null) JarvisAccessibilityService.openPanel(this)
            else {
                input.requestFocus()
                requestMic()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppPrefs.backgroundColor(this@MainActivity))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        header.addView(logo)
        header.addView(TextView(this).apply {
            text = "Jarvis1"
            textSize = 21f
            setTextColor(AppPrefs.textColor(this@MainActivity))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(topButton("New chat") { newChat() })
        header.addView(topButton("⋮") { showMenu() })
        header.addView(topButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(header)

        messagesView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            setBackgroundColor(AppPrefs.backgroundColor(this@MainActivity))
        }
        adapter = ChatAdapter(::showMessageMenu)
        messagesView.adapter = adapter
        root.addView(messagesView, LinearLayout.LayoutParams(-1, 0, 1f))

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.addView(topButton("Code") { startActivity(Intent(this@MainActivity, CodingAssistantActivity::class.java)) })
        tools.addView(topButton("Translate") { startActivity(Intent(this@MainActivity, TranslateActivity::class.java)) })
        tools.addView(topButton("Photo") { choosePhotoSource() })
        tools.addView(topButton("Notes") { showNotes() })
        tools.addView(topButton("Export") { exportChat() })
        tools.addView(topButton("Chats") { showChatHistory() })
        liveButton = topButton("Live") {
            if (liveModeActive) stopLiveSession() else requestLiveSession()
        }
        tools.addView(liveButton)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        })

        val promptRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val savedPrompts = AppPrefs.get(this, "quick_prompts").lineSequence()
            .map(String::trim).filter(String::isNotBlank).toList()
        val prompts = savedPrompts.ifEmpty {
            listOf("Summarize this for me", "Make a simple step-by-step plan", "Explain this in simple words")
        }
        prompts.forEach { prompt ->
            promptRow.addView(topButton(prompt) {
                input.setText("$prompt: ")
                input.setSelection(input.text.length)
                input.requestFocus()
            })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(promptRow)
        }, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = rounded(AppPrefs.surfaceColor(this@MainActivity), 28)
        }
        input = EditText(this).apply {
            hint = "Message Jarvis..."
            setHintTextColor(AppPrefs.mutedTextColor(this@MainActivity))
            setTextColor(AppPrefs.textColor(this@MainActivity))
            textSize = 16f
            maxLines = 5
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconButton("🎙") { requestMic() })
        sendButton = iconButton("➤") { if (sending) stopResponse() else sendCurrent() }
        row.addView(sendButton)
        root.addView(row)
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 90)
    }

    fun requestCameraAction(action: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ActionDispatcher.execute(this, org.json.JSONObject(action)) { toast(it) }
        } else {
            pendingCameraAction = action
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 93)
        }
    }

    @Deprecated("Legacy callback for Android runtime permissions.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 93) {
            val action = pendingCameraAction
            pendingCameraAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && action != null) {
                ActionDispatcher.execute(this, org.json.JSONObject(action)) { toast(it) }
            } else if (action != null) toast("Camera permission is needed for flashlight control.")
        }
    }

    private fun loadMessages() {
        scope.launch {
            adapter.submit(ChatDatabase.get(this@MainActivity).chatDao().messages(chatId))
            messagesView.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun sendCurrent() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (sending) return
        if (liveModeActive) stopLiveSession()
        input.text.clear()
        stopRequested = false
        sending = true
        sendButton.text = "Stop"
        sendJob = scope.launch {
            try {
                sendMessage(text)
            } finally {
                sending = false
                stopRequested = false
                activeCall = null
                sendButton.text = "➤"
                sendButton.isEnabled = true
            }
        }
    }

    private fun stopResponse() {
        stopRequested = true
        activeCall?.cancel()
        sendJob?.cancel()
        sendButton.text = "Stopping…"
        sendButton.isEnabled = false
        toast("Stopping response…")
    }

    private suspend fun sendMessage(
        text: String,
        extraContext: String? = null,
        persistUser: Boolean = true
    ) {
        val dao = ChatDatabase.get(this).chatDao()
        val normalized = AppPrefs.resolveShortcut(this, text.trim())
        if (normalized.startsWith("save note ", ignoreCase = true)) {
            val note = normalized.substringAfter(' ').substringAfter(' ').trim()
            if (note.isNotBlank()) {
                if (persistUser) dao.insert(ChatMessage(chatId = chatId, role = "user", content = normalized))
                dao.insertNote(UserNote(text = note))
                dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = "Saved that note."))
                if (persistUser) adapter.append(ChatMessage(chatId = chatId, role = "user", content = normalized))
                adapter.append(ChatMessage(chatId = chatId, role = "assistant", content = "Saved that note."))
                return
            }
        }
        if (normalized.equals("show my notes", ignoreCase = true)) {
            showNotes()
            return
        }
        if (normalized.startsWith("good morning", ignoreCase = true)) {
            ActionDispatcher.execute(this, org.json.JSONObject().put("type", "routine").put("name", "good morning")) { toast(it) }
        }
        scheduleNaturalReminder(normalized)?.let { message ->
            if (persistUser) dao.insert(ChatMessage(chatId = chatId, role = "user", content = normalized))
            dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = message))
            if (persistUser) adapter.append(ChatMessage(chatId = chatId, role = "user", content = normalized))
            adapter.append(ChatMessage(chatId = chatId, role = "assistant", content = message))
            return
        }
        val userMessage = if (extraContext == null) normalized else "$normalized\n$extraContext"
        if (persistUser) {
            dao.insert(ChatMessage(chatId = chatId, role = "user", content = userMessage))
            adapter.append(ChatMessage(chatId = chatId, role = "user", content = normalized))
        }
        messagesView.scrollToPosition(adapter.itemCount - 1)
        val placeholder = ChatMessage(chatId = chatId, role = "assistant", content = "…")
        adapter.append(placeholder)
        try {
            val prior = dao.latest(chatId, 20).asReversed().map { it.role to it.content }
            val reply = GroqClient.stream(this, prior, onText = { partial ->
                runOnUiThread { adapter.updateLast(GroqClient.visibleStreamingText(partial).ifBlank { "…" }) }
            }, onCall = { call ->
                if (stopRequested) call?.cancel() else activeCall = call
            })
            dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = reply.text))
            adapter.updateLast(reply.text)
            messagesView.scrollToPosition(adapter.itemCount - 1)
            speak(reply.text)
            reply.action?.let { ActionDispatcher.execute(this, it) { message -> toast(message) } }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) {
                adapter.updateLast("Response stopped.")
                throw error
            }
            if (stopRequested) {
                adapter.updateLast("Response stopped.")
                return
            }
            adapter.updateLast("Error: ${error.message ?: "Unable to contact the API."}")
        }
    }

    private fun scheduleNaturalReminder(text: String): String? {
        val match = Regex("""remind me in (\d+)\s*(minute|minutes|min|hour|hours)\s+to\s+(.+)""", RegexOption.IGNORE_CASE)
            .find(text) ?: Regex("""remind me at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+to\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (match == null) return null
        val minutes: Long
        val reminder: String
        if (match.groupValues[2].equals("minute", true) || match.groupValues[2].equals("minutes", true) || match.groupValues[2].equals("min", true)) {
            minutes = match.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            reminder = match.groupValues[3]
        } else if (match.groupValues[2].equals("hour", true) || match.groupValues[2].equals("hours", true)) {
            minutes = (match.groupValues[1].toLongOrNull() ?: 1L).coerceAtLeast(1) * 60
            reminder = match.groupValues[3]
        } else {
            val hourValue = match.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: return null
            val minuteValue = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
            val suffix = match.groupValues[3].lowercase()
            val hour = if (suffix == "pm") (hourValue % 12) + 12 else if (suffix == "am") hourValue % 12 else hourValue
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minuteValue)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            minutes = ((calendar.timeInMillis - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
            reminder = match.groupValues[4]
        }
        ActionDispatcher.execute(this, org.json.JSONObject().put("type", "reminder")
            .put("text", reminder).put("minutes", minutes)) { toast(it) }
        return "Reminder set for $reminder."
    }

    private fun requestMic() {
        if (liveModeActive) stopLiveSession()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLiveSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startLiveSession()
        } else {
            pendingLiveStart = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startLiveSession() {
        pendingLiveStart = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this device.")
            return
        }
        try {
            liveRecognizer?.destroy()
            liveRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        liveListening = false
                    }

                    override fun onError(error: Int) {
                        liveListening = false
                        if (!liveModeActive) return
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            stopLiveSession()
                            toast("Microphone permission is required for Live mode.")
                        } else if (error in LIVE_RETRYABLE_SPEECH_ERRORS) {
                            liveHandler.removeCallbacks(restartLiveListening)
                            liveHandler.postDelayed(restartLiveListening, LIVE_RETRY_DELAY_MS)
                        } else {
                            stopLiveSession()
                            toast("Live speech recognition stopped (error $error).")
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        liveListening = false
                        val recognized = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (recognized.isBlank()) {
                            scheduleLiveListening()
                        } else {
                            handleLiveUtterance(recognized)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            liveModeActive = true
            liveButton.text = "End live"
            toast("Live mode is on. Speak naturally; tap End live to finish.")
            startLiveListening()
        } catch (error: Exception) {
            liveModeActive = false
            liveRecognizer?.destroy()
            liveRecognizer = null
            liveButton.text = "Live"
            android.util.Log.e("JarvisLive", "Could not start live voice session.", error)
            toast(error.message ?: "Could not start Live mode.")
        }
    }

    private fun startLiveListening() {
        if (!liveModeActive || liveListening || liveAwaitingResponse || liveSpeechPending || sending) return
        val recognizer = liveRecognizer ?: return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            recognizer.startListening(intent)
            liveListening = true
        } catch (error: Exception) {
            liveListening = false
            android.util.Log.e("JarvisLive", "Could not start listening for the next turn.", error)
            scheduleLiveListening()
        }
    }

    private fun scheduleLiveListening() {
        if (!liveModeActive) return
        liveHandler.removeCallbacks(restartLiveListening)
        liveHandler.postDelayed(restartLiveListening, LIVE_RETRY_DELAY_MS)
    }

    private fun handleLiveUtterance(text: String) {
        if (!liveModeActive || liveAwaitingResponse) return
        input.setText(text)
        liveAwaitingResponse = true
        sending = true
        sendButton.text = "Stop"
        sendButton.isEnabled = true
        stopRequested = false
        sendJob = scope.launch {
            try {
                sendMessage(text)
            } finally {
                liveAwaitingResponse = false
                sending = false
                stopRequested = false
                activeCall = null
                sendButton.text = "➤"
                sendButton.isEnabled = true
                if (liveModeActive && !liveSpeechPending) scheduleLiveListening()
            }
        }
    }

    private fun onLiveSpeechFinished() {
        liveSpeechPending = false
        if (liveModeActive && !liveAwaitingResponse) scheduleLiveListening()
    }

    private fun stopLiveSession() {
        val wasLiveMode = liveModeActive
        val stopLiveTurn = wasLiveMode && liveAwaitingResponse
        val stopLiveSpeech = wasLiveMode && ::tts.isInitialized && tts.isSpeaking
        pendingLiveStart = false
        liveModeActive = false
        liveListening = false
        liveAwaitingResponse = false
        liveSpeechPending = false
        liveHandler.removeCallbacks(restartLiveListening)
        val recognizer = liveRecognizer
        liveRecognizer = null
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (error: Exception) {
            android.util.Log.w("JarvisLive", "Could not release live speech recognizer.", error)
        }
        if (stopLiveTurn) {
            stopRequested = true
            activeCall?.cancel()
            sendJob?.cancel()
        }
        if (stopLiveSpeech) tts.stop()
        if (::liveButton.isInitialized) liveButton.text = "Live"
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this device.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        try {
            startActivityForResult(intent, 92)
        } catch (error: Exception) {
            toast(error.message ?: "Could not start speech recognition.")
        }
    }

    @Deprecated("Legacy callback for speech recognition activity result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 92 && resultCode == RESULT_OK) {
            input.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())
        }
    }

    private fun handlePickedImage(uri: Uri) {
        scope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected image.")
                answerImage(bytes, contentResolver.getType(uri) ?: "image/jpeg")
            } catch (error: Exception) {
                toast(error.message ?: "Image request failed.")
            }
        }
    }

    private fun handleImageBytes(bytes: ByteArray, mime: String) {
        scope.launch {
            try {
                answerImage(bytes, mime)
            } catch (error: Exception) {
                toast(error.message ?: "Image request failed.")
            }
        }
    }

    private suspend fun answerImage(bytes: ByteArray, mime: String) {
        val dataUrl = "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val question = input.text.toString().trim().ifBlank { "Describe this image." }
        input.text.clear()
        val userText = "🖼 $question"
        adapter.append(ChatMessage(chatId = chatId, role = "user", content = userText))
        val dao = ChatDatabase.get(this).chatDao()
        dao.insert(ChatMessage(chatId = chatId, role = "user", content = userText))
        val answer = GroqClient.answerImage(this, dataUrl, question)
        adapter.append(ChatMessage(chatId = chatId, role = "assistant", content = answer))
        dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = answer))
        speak(answer)
    }

    private fun choosePhotoSource() {
        AlertDialog.Builder(this).setTitle("Image").setItems(arrayOf("Pick a photo", "Take a photo")) { _, which ->
            if (which == 0) imagePicker.launch("image/*") else cameraPicker.launch(null)
        }.show()
    }

    private fun showMenu() {
        val options = arrayOf(
            "Coding Assistant", "Translate", "New chat", "Clear history", "Export chat",
            "Show notes", "Save note", "Chat history", "Export all chats", "Recent actions", "Screen Control help"
        )
        AlertDialog.Builder(this).setTitle("Jarvis1").setItems(options) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, CodingAssistantActivity::class.java))
                1 -> startActivity(Intent(this, TranslateActivity::class.java))
                2 -> newChat()
                3 -> AlertDialog.Builder(this).setMessage("Delete all saved chat history?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        scope.launch {
                            ChatDatabase.get(this@MainActivity).chatDao().clearAll()
                            AppPrefs.clearChatMetadata(this@MainActivity)
                            adapter.submit(emptyList())
                            newChat()
                        }
                    }.show()
                4 -> exportChat()
                5 -> showNotes()
                6 -> promptSaveNote()
                7 -> showChatHistory()
                8 -> exportAllChats()
                9 -> showRecentActions()
                10 -> showAccessibilityHelp()
            }
        }.show()
    }

    private fun showChatHistory(query: String = "") {
        scope.launch {
            val dao = ChatDatabase.get(this@MainActivity).chatDao()
            val chats = (if (query.isBlank()) dao.chatSummaries() else {
                val byMessage = dao.searchChats(query)
                val byTitle = dao.chatSummaries().filter {
                    AppPrefs.chatTitle(this@MainActivity, it.chatId).contains(query, ignoreCase = true)
                }
                (byMessage + byTitle).distinctBy(ChatSummary::chatId)
            })
                .sortedWith(compareByDescending<ChatSummary> { AppPrefs.isChatPinned(this@MainActivity, it.chatId) }
                    .thenByDescending { it.updatedAt })
            if (chats.isEmpty()) {
                toast(if (query.isBlank()) "No saved chats yet." else "No chats matched that search.")
                return@launch
            }
            val labels = chats.map { chat ->
                val savedTitle = AppPrefs.chatTitle(this@MainActivity, chat.chatId)
                val title = savedTitle.ifBlank { chat.preview.orEmpty().lineSequence().firstOrNull().orEmpty() }
                    .ifBlank { "New chat" }.take(48)
                "${if (AppPrefs.isChatPinned(this@MainActivity, chat.chatId)) "★ " else ""}$title"
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (query.isBlank()) "Chat history" else "Search results")
                .setItems(labels) { _, index -> showChatOptions(chats[index], labels[index].removePrefix("★ ")) }
                .setNeutralButton("Search") { _, _ -> promptChatSearch() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun promptChatSearch() {
        val field = EditText(this).apply { hint = "Search chats and messages" }
        AlertDialog.Builder(this).setTitle("Search chat history").setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ -> showChatHistory(field.text.toString().trim()) }
            .show()
    }

    private fun showChatOptions(chat: ChatSummary, title: String) {
        val pinned = AppPrefs.isChatPinned(this, chat.chatId)
        val options = arrayOf("Open", "Rename", if (pinned) "Unpin" else "Pin", "Delete")
        AlertDialog.Builder(this).setTitle(title).setItems(options) { _, index ->
            when (index) {
                0 -> {
                    chatId = chat.chatId
                    AppPrefs.put(this, "current_chat_id", chatId)
                    loadMessages()
                }
                1 -> promptRenameChat(chat.chatId, title)
                2 -> {
                    AppPrefs.setChatPinned(this, chat.chatId, !pinned)
                    showChatHistory()
                }
                3 -> AlertDialog.Builder(this).setMessage("Delete this chat permanently?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        scope.launch {
                            ChatDatabase.get(this@MainActivity).chatDao().clearChat(chat.chatId)
                            AppPrefs.setChatTitle(this@MainActivity, chat.chatId, "")
                            AppPrefs.setChatPinned(this@MainActivity, chat.chatId, false)
                            if (chatId == chat.chatId) newChat()
                            showChatHistory()
                        }
                    }.show()
            }
        }.show()
    }

    private fun promptRenameChat(id: String, title: String) {
        val field = EditText(this).apply { setText(title) }
        AlertDialog.Builder(this).setTitle("Rename chat").setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = field.text.toString().trim()
                if (newTitle.isBlank()) toast("Chat name cannot be empty.")
                else {
                    AppPrefs.setChatTitle(this, id, newTitle)
                    showChatHistory()
                }
            }.show()
    }

    private fun newChat() {
        chatId = UUID.randomUUID().toString()
        AppPrefs.put(this, "current_chat_id", chatId)
        adapter.submit(emptyList())
    }

    private fun showMessageMenu(message: ChatMessage) {
        val options = if (message.role == "assistant") arrayOf("Copy", "Share", "Regenerate last answer")
        else arrayOf("Copy", "Share")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            if (which == 0) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Jarvis message", message.content))
                toast("Copied")
            } else if (which == 1) shareText(message.content)
            else regenerateLastAnswer()
        }.show()
    }

    private fun regenerateLastAnswer() {
        if (sending) {
            toast("Stop the current response before regenerating.")
            return
        }
        sendJob = scope.launch {
            val dao = ChatDatabase.get(this@MainActivity).chatDao()
            val lastUser = dao.latestUserMessage(chatId)
            if (lastUser == null) {
                toast("There is no question to regenerate.")
                return@launch
            }
            dao.deleteLatestAssistantMessage(chatId)
            adapter.submit(dao.messages(chatId))
            messagesView.scrollToPosition(adapter.itemCount - 1)
            stopRequested = false
            sending = true
            sendButton.text = "Stop"
            try {
                sendMessage(lastUser.content, persistUser = false)
            } finally {
                sending = false
                stopRequested = false
                activeCall = null
                sendButton.text = "➤"
                sendButton.isEnabled = true
            }
        }
    }

    private fun exportChat() {
        scope.launch {
            val text = ChatDatabase.get(this@MainActivity).chatDao().messages(chatId)
                .joinToString("\n\n") { "${if (it.role == "user") "You" else "Jarvis"}: ${it.content}" }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity, "$packageName.files",
                java.io.File(cacheDir, "jarvis-chat.txt").apply { writeText(text) }
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export chat"))
        }
    }

    private fun exportAllChats() {
        scope.launch {
            val dao = ChatDatabase.get(this@MainActivity).chatDao()
            val sections = mutableListOf<String>()
            for (chat in dao.chatSummaries()) {
                val title = AppPrefs.chatTitle(this@MainActivity, chat.chatId)
                    .ifBlank { chat.preview.orEmpty().take(48) }
                val messages = dao.messages(chat.chatId).joinToString("\n\n") {
                    "${if (it.role == "user") "You" else "Jarvis"}: ${it.content}"
                }
                sections += "=== $title ===\n$messages"
            }
            val content = sections.joinToString("\n\n")
            shareExport(content, "jarvis-all-chats.txt", "Export all chats")
        }
    }

    private fun shareExport(content: String, filename: String, chooserTitle: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.files", java.io.File(cacheDir, filename).apply { writeText(content) }
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, chooserTitle))
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share"))
    }

    private fun promptSaveNote() {
        val field = EditText(this).apply { hint = "Note" }
        AlertDialog.Builder(this).setTitle("Save note").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                scope.launch {
                    val text = field.text.toString().trim()
                    if (text.isNotEmpty()) ChatDatabase.get(this@MainActivity).chatDao().insertNote(UserNote(text = text))
                }
            }.show()
    }

    private fun showNotes(query: String = "") {
        scope.launch {
            val dao = ChatDatabase.get(this@MainActivity).chatDao()
            val notes = if (query.isBlank()) dao.notes() else dao.searchNotes(query)
            val labels = notes.map { it.text }.toTypedArray()
            if (labels.isEmpty()) {
                toast(if (query.isBlank()) "No saved notes." else "No notes matched that search.")
                return@launch
            }
            AlertDialog.Builder(this@MainActivity).setTitle(if (query.isBlank()) "Notes" else "Note search")
                .setItems(labels) { _, index -> showNoteOptions(notes[index]) }
                .setNeutralButton("Search") { _, _ -> promptNoteSearch() }
                .setPositiveButton("Add") { _, _ -> promptSaveNote() }
                .show()
        }
    }

    private fun promptNoteSearch() {
        val field = EditText(this).apply { hint = "Search saved notes" }
        AlertDialog.Builder(this).setTitle("Search notes").setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ -> showNotes(field.text.toString().trim()) }
            .show()
    }

    private fun showNoteOptions(note: UserNote) {
        AlertDialog.Builder(this).setTitle(note.text.take(60))
            .setItems(arrayOf("Read aloud", "Set reminder", "Delete")) { _, index ->
                when (index) {
                    0 -> speak(note.text)
                    1 -> promptNoteReminder(note.text)
                    2 -> AlertDialog.Builder(this).setMessage("Delete this note?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            scope.launch {
                                ChatDatabase.get(this@MainActivity).chatDao().deleteNote(note.id)
                                showNotes()
                            }
                        }.show()
                }
            }.show()
    }

    private fun promptNoteReminder(text: String) {
        val field = EditText(this).apply {
            hint = "Minutes from now"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("30")
        }
        AlertDialog.Builder(this).setTitle("Remind me in…").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Set reminder") { _, _ ->
                val minutes = field.text.toString().toLongOrNull()?.takeIf { it > 0 }
                if (minutes == null) toast("Enter a number of minutes greater than zero.")
                else ActionDispatcher.execute(this, org.json.JSONObject()
                    .put("type", "reminder").put("text", text).put("minutes", minutes)) { toast(it) }
            }.show()
    }

    private fun showRecentActions() {
        val actions = AppPrefs.recentActions(this)
        val labels = actions.map { action ->
            val type = action.optString("type").replace('_', ' ')
            val detail = action.optString("detail").takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            "$type$detail"
        }.toTypedArray()
        if (labels.isEmpty()) {
            toast("No recent actions.")
            return
        }
        AlertDialog.Builder(this).setTitle("Recent actions").setItems(labels, null)
            .setNeutralButton("Undo last volume change") { _, _ ->
                if (ActionDispatcher.undoLastSupportedAction(this)) toast("Undid the last volume change.")
                else toast("The most recent action cannot be undone.")
            }.setPositiveButton("Done", null).show()
    }

    private fun showAccessibilityHelp() {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/${JarvisAccessibilityService::class.java.name}") == true
        val detail = if (enabled) {
            "Screen Control is enabled. If the bubble is missing, check the Jarvis bubble setting and re-enable the service."
        } else {
            "Screen Control is disabled. Enable Jarvis1 in Accessibility settings. If Android blocks it, open App info, use the menu, and allow restricted settings."
        }
        AlertDialog.Builder(this).setTitle("Screen Control")
            .setMessage(detail)
            .setNegativeButton("Close", null)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }
    private fun speak(text: String) {
        if (AppPrefs.bool(this, "speak_replies")) {
            tts.language = speechLocale()
            tts.setSpeechRate(AppPrefs.speechRate(this))
            val utteranceId = if (liveModeActive) {
                liveSpeechPending = true
                LIVE_UTTERANCE_ID
            } else {
                "jarvis-reply"
            }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (liveModeActive && result == TextToSpeech.ERROR) onLiveSpeechFinished()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = speechLocale()
            tts.setSpeechRate(AppPrefs.speechRate(this))
        }
    }

    private fun speechLocale(): Locale = when (AppPrefs.get(this, "voice_language", "English")) {
        "Urdu" -> Locale("ur")
        "Hindi" -> Locale("hi")
        else -> Locale.ENGLISH
    }

    private fun topButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setTextColor(AppPrefs.textColor(this@MainActivity))
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun iconButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        minWidth = dp(42)
        backgroundTintList = android.content.res.ColorStateList.valueOf(AppPrefs.accentColor(this@MainActivity))
        setTextColor(AppPrefs.textColor(this@MainActivity))
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val LIVE_UTTERANCE_ID = "jarvis-live-reply"
        private const val LIVE_RETRY_DELAY_MS = 700L
        private val LIVE_RETRYABLE_SPEECH_ERRORS = setOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED
        )
        const val EXTRA_SCREEN_TEXT = "screen_text"
        const val EXTRA_GAME_ACTION = "game_action"
        const val EXTRA_SHOW_NOTES = "show_notes"
        const val EXTRA_OPEN_PANEL = "open_command_panel"
        const val EXTRA_HOTWORD_TEXT = "hotword_text"
        const val EXTRA_CAMERA_ACTION = "camera_action"
        const val EXTRA_SPEAK_TEXT = "speak_text"
    }

    override fun onDestroy() {
        stopLiveSession()
        super.onDestroy()
        scope.cancel()
        tts.stop()
        tts.shutdown()
    }

    override fun onStop() {
        if (liveModeActive) stopLiveSession()
        super.onStop()
    }
}

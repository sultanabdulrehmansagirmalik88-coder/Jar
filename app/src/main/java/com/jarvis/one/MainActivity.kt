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
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val scope = MainScope()
    private lateinit var messagesView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var chatId: String = ""
    private var pendingCameraAction: String? = null
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
        if (granted) listen() else toast("Microphone permission is required for voice input.")
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
        root.addView(tools)

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
        row.addView(iconButton("➤") { sendCurrent() })
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
        input.text.clear()
        scope.launch { sendMessage(text) }
    }

    private suspend fun sendMessage(text: String, extraContext: String? = null) {
        val dao = ChatDatabase.get(this).chatDao()
        val normalized = text.trim()
        if (normalized.startsWith("save note ", ignoreCase = true)) {
            val note = normalized.substringAfter(' ').substringAfter(' ').trim()
            if (note.isNotBlank()) {
                dao.insertNote(UserNote(text = note))
                dao.insert(ChatMessage(chatId = chatId, role = "user", content = text))
                dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = "Saved that note."))
                adapter.append(ChatMessage(chatId = chatId, role = "user", content = text))
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
            dao.insert(ChatMessage(chatId = chatId, role = "user", content = text))
            dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = message))
            adapter.append(ChatMessage(chatId = chatId, role = "user", content = text))
            adapter.append(ChatMessage(chatId = chatId, role = "assistant", content = message))
            return
        }
        val userMessage = if (extraContext == null) text else "$text\n$extraContext"
        dao.insert(ChatMessage(chatId = chatId, role = "user", content = userMessage))
        adapter.append(ChatMessage(chatId = chatId, role = "user", content = text))
        messagesView.scrollToPosition(adapter.itemCount - 1)
        val placeholder = ChatMessage(chatId = chatId, role = "assistant", content = "…")
        adapter.append(placeholder)
        try {
            val prior = dao.latest(chatId, 20).asReversed().map { it.role to it.content }
            val reply = GroqClient.stream(this, prior, onText = { partial ->
                runOnUiThread { adapter.updateLast(GroqClient.visibleStreamingText(partial).ifBlank { "…" }) }
            })
            dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = reply.text))
            adapter.updateLast(reply.text)
            messagesView.scrollToPosition(adapter.itemCount - 1)
            speak(reply.text)
            reply.action?.let { ActionDispatcher.execute(this, it) { message -> toast(message) } }
        } catch (error: Exception) {
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
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
        val options = arrayOf("Coding Assistant", "Translate", "New chat", "Clear history", "Export chat", "Show notes", "Save note")
        AlertDialog.Builder(this).setTitle("Jarvis1").setItems(options) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, CodingAssistantActivity::class.java))
                1 -> startActivity(Intent(this, TranslateActivity::class.java))
                2 -> newChat()
                3 -> AlertDialog.Builder(this).setMessage("Delete all saved chat history?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        scope.launch {
                            ChatDatabase.get(this@MainActivity).chatDao().clearAll()
                            adapter.submit(emptyList())
                            newChat()
                        }
                    }.show()
                4 -> exportChat()
                5 -> showNotes()
                6 -> promptSaveNote()
            }
        }.show()
    }

    private fun newChat() {
        chatId = UUID.randomUUID().toString()
        AppPrefs.put(this, "current_chat_id", chatId)
        adapter.submit(emptyList())
    }

    private fun showMessageMenu(message: ChatMessage) {
        AlertDialog.Builder(this).setItems(arrayOf("Copy", "Share")) { _, which ->
            if (which == 0) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Jarvis message", message.content))
                toast("Copied")
            } else shareText(message.content)
        }.show()
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

    private fun showNotes() {
        scope.launch {
            val notes = ChatDatabase.get(this@MainActivity).chatDao().notes()
            val labels = notes.map { it.text }.toTypedArray()
            if (labels.isEmpty()) {
                toast("No saved notes.")
                return@launch
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Notes").setItems(labels) { _, index ->
                AlertDialog.Builder(this@MainActivity).setMessage(notes[index].text)
                    .setNegativeButton("Close", null).setPositiveButton("Delete") { _, _ ->
                        scope.launch { ChatDatabase.get(this@MainActivity).chatDao().deleteNote(notes[index].id); showNotes() }
                    }.show()
            }.setNeutralButton("Add") { _, _ -> promptSaveNote() }.show()
        }
    }

    private fun speak(text: String) {
        if (AppPrefs.bool(this, "speak_replies")) {
            tts.language = speechLocale()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = speechLocale()
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
        const val EXTRA_SCREEN_TEXT = "screen_text"
        const val EXTRA_GAME_ACTION = "game_action"
        const val EXTRA_SHOW_NOTES = "show_notes"
        const val EXTRA_OPEN_PANEL = "open_command_panel"
        const val EXTRA_HOTWORD_TEXT = "hotword_text"
        const val EXTRA_CAMERA_ACTION = "camera_action"
        const val EXTRA_SPEAK_TEXT = "speak_text"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tts.stop()
        tts.shutdown()
    }
}

package com.jarvis.one

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.math.abs

class JarvisAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var bubble: ImageButton? = null
    private var panel: View? = null
    private var panelReply: TextView? = null
    private var gameRunning = false
    private var bubbleTts: android.speech.tts.TextToSpeech? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceReference = WeakReference(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removePanel()
        removeBubble()
        bubbleTts?.shutdown()
        bubbleTts = null
        if (instance === this) serviceReference = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    fun showBubble() {
        if (bubble != null) return
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xff3978f6.toInt())
            }
            contentDescription = "Open Jarvis command panel"
            setPadding(0, 0, 0, 0)
        }
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (56 * density).toInt(), (56 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (180 * density).toInt()
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var dragged = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        downX = event.rawX
                        downY = event.rawY
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) + abs(dy) > 8) dragged = true
                        params.x = startX - dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) {
                            view.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
        button.setOnClickListener {
            if (gameRunning || GameAgentService.isRunning) {
                stopService(android.content.Intent(this@JarvisAccessibilityService, GameAgentService::class.java))
                gameRunning = false
                updateBubbleState()
            } else showPanel()
        }
        try {
            windowManager.addView(button, params)
            bubble = button
            updateBubbleState()
        } catch (error: Exception) {
            Toast.makeText(this, "Could not show Jarvis bubble: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateBubbleState() {
        bubble?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (gameRunning || GameAgentService.isRunning) 0xffd93333.toInt() else 0xff3978f6.toInt())
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff171c26.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xff526079.toInt())
            }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Jarvis1"
            textSize = 17f
            setTextColor(-1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close"
            setOnClickListener { removePanel() }
        }
        header.addView(close)
        root.addView(header)
        val field = EditText(this).apply {
            hint = "Ask Jarvis..."
            setTextColor(-1)
            setHintTextColor(0xffbbbbbb.toInt())
            maxLines = 4
            setBackgroundColor(0xff252c38.toInt())
        }
        root.addView(field)
        panelReply = TextView(this).apply {
            setTextColor(-1)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(panelReply)
        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        buttons.addView(android.widget.Button(this).apply {
            text = "Mic"
            setOnClickListener { listenInPanel(field) }
        })
        buttons.addView(android.widget.Button(this).apply {
            text = "Send"
            setOnClickListener { submitPanel(field.text.toString().trim()) }
        })
        root.addView(buttons)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(32)
        }
        try {
            windowManager.addView(root, params)
            panel = root
            field.requestFocus()
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            windowManager.updateViewLayout(root, params)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            Handler(Looper.getMainLooper()).postDelayed({ imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 250)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not open command panel: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun listenInPanel(field: EditText) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open Jarvis1 and allow microphone permission first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { recognizer.destroy() }
            override fun onResults(results: android.os.Bundle?) {
                field.setText(results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        recognizer.startListening(intent)
    }

    private fun submitPanel(text: String) {
        if (text.isBlank()) return
        panelReply?.text = "…"
        scope.launch {
            try {
                val dao = ChatDatabase.get(this@JarvisAccessibilityService).chatDao()
                val chatId = AppPrefs.get(this@JarvisAccessibilityService, "current_chat_id")
                    .ifBlank { "bubble-default" }
                dao.insert(ChatMessage(chatId = chatId, role = "user", content = text))
                val history = dao.latest(chatId, 20).asReversed().map { it.role to it.content }
                val reply = GroqClient.stream(this@JarvisAccessibilityService, history, { partial ->
                    Handler(Looper.getMainLooper()).post {
                        panelReply?.text = GroqClient.visibleStreamingText(partial).ifBlank { "…" }
                    }
                })
                dao.insert(ChatMessage(chatId = chatId, role = "assistant", content = reply.text))
                panelReply?.text = reply.text
                if (AppPrefs.bool(this@JarvisAccessibilityService, "speak_replies")) {
                    bubbleTts?.shutdown()
                    bubbleTts = android.speech.tts.TextToSpeech(this@JarvisAccessibilityService) { status ->
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            bubbleTts?.language = when (AppPrefs.get(this@JarvisAccessibilityService, "voice_language", "English")) {
                                "Urdu" -> Locale("ur")
                                "Hindi" -> Locale("hi")
                                else -> Locale.ENGLISH
                            }
                            bubbleTts?.speak(reply.text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "bubble-reply")
                        }
                    }
                }
                if (reply.action != null) {
                    removePanel()
                    ActionDispatcher.execute(this@JarvisAccessibilityService, reply.action) { message ->
                        Toast.makeText(this@JarvisAccessibilityService, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                panelReply?.text = "Error: ${error.message ?: "Request failed."}"
            }
        }
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelReply = null
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    fun performBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun performHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun performTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 400): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(100, 5000))).build(), null, null)
    }

    fun readScreen(): String {
        val result = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || result.length > 12000) return
            node.text?.toString()?.takeIf { it.isNotBlank() && !node.isPassword }?.let {
                if (result.length > 0) result.append('\n')
                result.append(it)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                visit(child, depth + 1)
                child.recycle()
            }
        }
        val candidateRoots = windows.mapNotNull { it.root }
        val externalRoot = candidateRoots.firstOrNull { it.packageName?.toString() != packageName }
        candidateRoots.filter { it !== externalRoot }.forEach { it.recycle() }
        val root = externalRoot ?: rootInActiveWindow ?: return ""
        visit(root, 0)
        root.recycle()
        return result.toString()
    }

    fun setFlashlight(enabled: Boolean) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("No flashlight is available.")
        manager.setTorchMode(cameraId, enabled)
    }

    fun setGameRunning(running: Boolean) {
        gameRunning = running
        Handler(Looper.getMainLooper()).post { updateBubbleState() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removePanel()
        removeBubble()
        bubbleTts?.shutdown()
        bubbleTts = null
        if (instance === this) serviceReference = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile private var serviceReference: WeakReference<JarvisAccessibilityService>? = null
        val instance: JarvisAccessibilityService? get() = serviceReference?.get()
        fun openPanel(context: Context) {
            instance?.showPanel() ?: context.startActivity(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

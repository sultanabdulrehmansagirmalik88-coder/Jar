package com.jarvis.one

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

class HandsFreeService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var screenOn = true
    private var serviceActive = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action == Intent.ACTION_SCREEN_ON
            if (screenOn) startListening() else stopListening()
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("jarvis_handsfree", "Jarvis hands-free", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(522, NotificationCompat.Builder(this, "jarvis_handsfree")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis hands-free is on")
            .setContentText("Listening for “Hey Jarvis” while the screen is on")
            .setOngoing(true).build())
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        startListening()
    }

    private fun startListening() {
        if (!serviceActive || !screenOn ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onResults(results: android.os.Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.contains("hey jarvis", ignoreCase = true)) {
            val command = text.substringAfter("hey jarvis", "").trim()
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HOTWORD_TEXT, command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (command.isBlank()) intent.putExtra(MainActivity.EXTRA_OPEN_PANEL, true)
            startActivity(intent)
        }
        startListening()
    }

    override fun onError(error: Int) {
        if (serviceActive && screenOn) android.os.Handler(mainLooper).postDelayed({ startListening() }, 900)
    }
    override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

    override fun onDestroy() {
        serviceActive = false
        stopListening()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

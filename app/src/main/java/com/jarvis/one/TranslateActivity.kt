package com.jarvis.one

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TranslateActivity : AppCompatActivity() {
    private lateinit var source: Spinner
    private lateinit var target: Spinner
    private lateinit var input: EditText
    private lateinit var output: TextView
    private var translatorTts: android.speech.tts.TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppPrefs.bool(this, "light_theme")) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(AppPrefs.backgroundColor(this@TranslateActivity))
        }
        root.addView(TextView(this).apply { text = "Translate"; textSize = 22f; setTextColor(AppPrefs.textColor(this@TranslateActivity)) })
        val langs = arrayOf("Urdu", "Hindi", "English")
        source = Spinner(this).apply { adapter = ArrayAdapter(this@TranslateActivity, android.R.layout.simple_spinner_dropdown_item, langs) }
        target = Spinner(this).apply {
            adapter = ArrayAdapter(this@TranslateActivity, android.R.layout.simple_spinner_dropdown_item, langs)
            setSelection(2)
        }
        val languageRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        languageRow.addView(source, LinearLayout.LayoutParams(0, -2, 1f))
        languageRow.addView(Button(this).apply {
            text = "⇄"
            setOnClickListener {
                val oldSource = source.selectedItemPosition
                source.setSelection(target.selectedItemPosition)
                target.setSelection(oldSource)
            }
        })
        languageRow.addView(target, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(languageRow)
        input = EditText(this).apply {
            hint = "Text to translate"
            setTextColor(AppPrefs.textColor(this@TranslateActivity))
            setHintTextColor(AppPrefs.mutedTextColor(this@TranslateActivity))
            minLines = 5
            gravity = Gravity.TOP
            setBackgroundColor(AppPrefs.surfaceColor(this@TranslateActivity))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        val translate = Button(this).apply { text = "Translate" }
        root.addView(translate)
        output = TextView(this).apply {
            textSize = 17f
            setTextColor(AppPrefs.textColor(this@TranslateActivity))
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        root.addView(output)
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Translation", output.text))
                Toast.makeText(this@TranslateActivity, "Copied.", Toast.LENGTH_SHORT).show()
            }
        })
        actions.addView(Button(this).apply {
            text = "Speak"
            setOnClickListener {
                translatorTts?.shutdown()
                translatorTts = android.speech.tts.TextToSpeech(this@TranslateActivity) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        val locale = when (target.selectedItem.toString()) {
                            "Urdu" -> java.util.Locale("ur")
                            "Hindi" -> java.util.Locale("hi")
                            else -> java.util.Locale.ENGLISH
                        }
                        translatorTts?.language = locale
                        translatorTts?.speak(output.text.toString(), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "translation")
                    }
                }
            }
        })
        root.addView(actions)
        translate.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                output.text = "Translating…"
                try {
                    output.text = GroqClient.complete(this@TranslateActivity,
                        listOf("user" to "Translate from ${source.selectedItem} to ${target.selectedItem}. Preserve meaning. Return only the translation:\n$text"),
                        "You are a concise translator.").text
                } catch (error: Exception) {
                    output.text = "Error: ${error.message ?: "Translation failed."}"
                }
            }
        }
        setContentView(root)
    }

    override fun onDestroy() {
        translatorTts?.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

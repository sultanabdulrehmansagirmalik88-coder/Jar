package com.jarvis.one

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class CodingAssistantActivity : AppCompatActivity() {
    private lateinit var mode: Spinner
    private lateinit var request: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppPrefs.bool(this, "light_theme")) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(AppPrefs.backgroundColor(this@CodingAssistantActivity))
        }
        root.addView(TextView(this).apply {
            text = "Coding Assistant"
            textSize = 22f
            setTextColor(AppPrefs.textColor(this@CodingAssistantActivity))
        })
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@CodingAssistantActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Generate", "Debug", "Explain"))
        }
        root.addView(mode)
        request = EditText(this).apply {
            hint = "Describe a task or paste code..."
            setHintTextColor(AppPrefs.mutedTextColor(this@CodingAssistantActivity))
            setTextColor(AppPrefs.textColor(this@CodingAssistantActivity))
            minLines = 5
            gravity = Gravity.TOP
            setBackgroundColor(AppPrefs.surfaceColor(this@CodingAssistantActivity))
        }
        root.addView(request, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this)
        val run = Button(this).apply { text = "Run"; backgroundTintList = android.content.res.ColorStateList.valueOf(AppPrefs.accentColor(this@CodingAssistantActivity)) }
        val copy = Button(this).apply { text = "Copy"; backgroundTintList = android.content.res.ColorStateList.valueOf(AppPrefs.accentColor(this@CodingAssistantActivity)) }
        controls.addView(run, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        output = TextView(this).apply {
            text = "Your answer will appear here."
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(AppPrefs.textColor(this@CodingAssistantActivity))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(AppPrefs.surfaceColor(this@CodingAssistantActivity))
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        run.setOnClickListener { runRequest() }
        copy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Coding assistant", output.text))
            toast("Copied.")
        }
        setContentView(root)
    }

    private fun runRequest() {
        val text = request.text.toString().trim()
        if (text.isEmpty()) return
        val selectedMode = mode.selectedItem.toString()
        output.text = "Thinking…"
        lifecycleScope.launch {
            try {
                val prompt = "You are a coding assistant. Mode: $selectedMode. Give short, correct code in fenced code blocks when appropriate. Explain in the same language as the user's request."
                val answer = GroqClient.complete(this@CodingAssistantActivity, listOf("user" to text), prompt)
                output.text = answer.text
            } catch (error: Exception) {
                output.text = "Error: ${error.message ?: "Request failed."}"
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}

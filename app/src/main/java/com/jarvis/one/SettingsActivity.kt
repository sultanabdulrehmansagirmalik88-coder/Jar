package com.jarvis.one

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var promptField: EditText
    private lateinit var persona: Spinner
    private lateinit var voiceLanguage: Spinner
    private lateinit var speakSwitch: SwitchMaterial
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var handsFreeSwitch: SwitchMaterial
    private lateinit var status: TextView
    private lateinit var routineField: EditText
    private lateinit var routineNameField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppPrefs.bool(this, "light_theme")) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
        super.onCreate(savedInstanceState)
        window.statusBarColor = AppPrefs.backgroundColor(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (AppPrefs.bool(this, "light_theme")) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(28))
            setBackgroundColor(AppPrefs.backgroundColor(this@SettingsActivity))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll)
        content.addView(title("Settings"))

        keyField = field("API key").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AppPrefs.get(this@SettingsActivity, "api_key"))
        }
        addField(content, keyField)
        modelField = field("Model").apply { setText(AppPrefs.get(this@SettingsActivity, "model", AppPrefs.DEFAULT_MODEL)) }
        addField(content, modelField)
        addButton(content, "Save") { save() }
        addButton(content, "Test API key") {
            save(applyTheme = false)
            lifecycleScope.launch {
                try {
                    val result = GroqClient.test(this@SettingsActivity)
                    toast(result)
                } catch (error: Exception) {
                    toast(error.message ?: "API test failed.")
                }
            }
        }

        content.addView(title("Screen Control"))
        status = label("")
        content.addView(status)
        content.addView(label("If Screen Control is greyed out: App info > menu (3 dots) > Allow restricted settings"))
        addButton(content, "Enable Screen Control") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        updateAccessibilityStatus()

        content.addView(title("Replies"))
        speakSwitch = SwitchMaterial(this).apply {
            text = "Speak replies"
            setTextColor(AppPrefs.textColor(this@SettingsActivity))
            isChecked = AppPrefs.bool(this@SettingsActivity, "speak_replies")
        }
        content.addView(speakSwitch)
        voiceLanguage = spinner(arrayOf("English", "Urdu", "Hindi"), AppPrefs.get(this, "voice_language", "English"))
        addSpinner(content, "Voice language", voiceLanguage)

        content.addView(title("Assistant style"))
        persona = spinner(arrayOf("Jarvis", "Friendly", "Girlfriend (Yasmin style)", "Coding Teacher"),
            AppPrefs.get(this, "persona", "Jarvis"))
        addSpinner(content, "Persona", persona)
        promptField = field("Custom system prompt (optional)").apply {
            minLines = 3
            gravity = Gravity.TOP
            setText(AppPrefs.get(this@SettingsActivity, "system_prompt"))
        }
        addField(content, promptField)
        addButton(content, "Reset to default") { promptField.setText("") }

        content.addView(title("Appearance"))
        themeSwitch = SwitchMaterial(this).apply {
            text = "Light theme"
            setTextColor(AppPrefs.textColor(this@SettingsActivity))
            isChecked = AppPrefs.bool(this@SettingsActivity, "light_theme")
        }
        content.addView(themeSwitch)
        addButton(content, "Choose accent colour") {
            val colors = arrayOf("Blue", "Purple", "Teal", "Orange")
            android.app.AlertDialog.Builder(this).setTitle("Accent colour").setItems(colors) { _, which ->
                AppPrefs.put(this, "accent", colors[which])
                toast("Accent colour set to ${colors[which]}. Reopen the app to apply.")
            }.show()
        }

        content.addView(title("Hands-free"))
        handsFreeSwitch = SwitchMaterial(this).apply {
            text = "Listen for “Hey Jarvis” while screen is on"
            setTextColor(AppPrefs.textColor(this@SettingsActivity))
            isChecked = AppPrefs.bool(this@SettingsActivity, "hands_free")
        }
        content.addView(handsFreeSwitch)

        content.addView(title("Routines"))
        routineNameField = field("Routine name").apply { setText("Good morning") }
        addField(content, routineNameField)
        routineField = field("Routine actions, one JSON object per line")
        routineField.minLines = 3
        routineField.gravity = Gravity.TOP
        routineField.setText(AppPrefs.get(this, "routine_good morning"))
        addField(content, routineField)
        addButton(content, "Load routine") {
            val routineName = routineNameField.text.toString().trim().ifBlank { "Good morning" }
            val saved = AppPrefs.get(this, "routine_${routineName.lowercase()}")
            if (saved.isEmpty()) toast("No routine named $routineName is saved.")
            else routineField.setText(saved)
        }
        addButton(content, "Save routine") {
            saveRoutine()
        }

        root.addView(addButtonView("Save all settings") { save() })
        setContentView(root)
    }

    private fun save(applyTheme: Boolean = true) {
        AppPrefs.put(this, "api_key", keyField.text.toString().trim())
        AppPrefs.put(this, "model", modelField.text.toString().trim().ifBlank { AppPrefs.DEFAULT_MODEL })
        AppPrefs.put(this, "system_prompt", promptField.text.toString().trim())
        AppPrefs.put(this, "persona", persona.selectedItem.toString())
        AppPrefs.put(this, "voice_language", voiceLanguage.selectedItem.toString())
        AppPrefs.putBool(this, "speak_replies", speakSwitch.isChecked)
        AppPrefs.putBool(this, "light_theme", themeSwitch.isChecked)
        AppPrefs.putBool(this, "hands_free", handsFreeSwitch.isChecked)
        saveRoutine()
        if (applyTheme) {
            AppCompatDelegate.setDefaultNightMode(
                if (themeSwitch.isChecked) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )
        }
        if (handsFreeSwitch.isChecked) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, HandsFreeService::class.java))
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 94)
            }
        } else stopService(Intent(this, HandsFreeService::class.java))
        toast("Settings saved.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 94) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, HandsFreeService::class.java))
            } else {
                handsFreeSwitch.isChecked = false
                AppPrefs.putBool(this, "hands_free", false)
                toast("Microphone permission is required for hands-free mode.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/${JarvisAccessibilityService::class.java.name}") == true
        status.text = "Screen Control is ${if (enabled) "On" else "Off"}"
    }

    private fun saveRoutine() {
        val name = routineNameField.text.toString().trim().ifBlank { "Good morning" }
        AppPrefs.put(this, "routine_${name.lowercase()}", routineField.text.toString())
        val names = AppPrefs.get(this, "routine_names").split('\n').filter { it.isNotBlank() }.toMutableSet()
        names.add(name)
        AppPrefs.put(this, "routine_names", names.joinToString("\n"))
        toast("$name routine saved.")
    }

    private fun spinner(values: Array<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(values.indexOf(selected).coerceAtLeast(0))
    }

    private fun addSpinner(parent: LinearLayout, caption: String, control: Spinner) {
        parent.addView(label(caption))
        parent.addView(control)
    }

    private fun addField(parent: LinearLayout, field: EditText) {
        val params = LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(8)
        parent.addView(field, params)
    }

    private fun addButton(parent: LinearLayout, text: String, click: () -> Unit) {
        parent.addView(addButtonView(text, click))
    }

    private fun addButtonView(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        backgroundTintList = android.content.res.ColorStateList.valueOf(AppPrefs.accentColor(this@SettingsActivity))
        setOnClickListener { click() }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(AppPrefs.mutedTextColor(this@SettingsActivity))
        setTextColor(AppPrefs.textColor(this@SettingsActivity))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(AppPrefs.surfaceColor(this@SettingsActivity))
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setTextColor(AppPrefs.textColor(this@SettingsActivity))
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(AppPrefs.mutedTextColor(this@SettingsActivity))
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}

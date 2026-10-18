package com.jarvis.one

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import kotlinx.coroutines.launch

object ActionDispatcher {
    fun execute(context: Context, action: JSONObject, notify: (String) -> Unit = {}) {
        val type = action.optString("type")
        try {
            when (type) {
                "open_app" -> openApp(context, action.optString("name"))
                "youtube" -> searchYoutube(context, action.optString("query"))
                "music" -> searchMusic(context, action.optString("query"))
                "back" -> JarvisAccessibilityService.instance?.performBack() ?: notify("Enable Screen Control to use Back.")
                "home" -> JarvisAccessibilityService.instance?.performHome() ?: notify("Enable Screen Control to use Home.")
                "tap" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) notify("Enable Screen Control to tap.")
                    else if (!service.performTap(action.optInt("x"), action.optInt("y"))) notify("Tap gesture could not be started.")
                }
                "swipe" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) notify("Enable Screen Control to swipe.")
                    else if (!service.performSwipe(action.optInt("x1"), action.optInt("y1"), action.optInt("x2"),
                            action.optInt("y2"), action.optLong("ms", 400))) notify("Swipe gesture could not be started.")
                }
                "read_screen" -> {
                    val screen = JarvisAccessibilityService.instance?.readScreen().orEmpty()
                    if (screen.isBlank()) notify("Screen text is unavailable. Enable Screen Control and try again.")
                    else {
                        val intent = Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_SCREEN_TEXT, screen)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(intent)
                    }
                }
                "game_start" -> requestGameStart(context, action)
                "game_stop" -> context.stopService(Intent(context, GameAgentService::class.java))
                "media" -> media(context, action.optString("cmd"), notify)
                "call", "sms" -> confirmSensitiveAction(context, action)
                "whatsapp" -> whatsapp(context, action.optString("name"), action.optString("text"))
                "alarm" -> context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, action.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, action.optInt("minute"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "timer" -> context.startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, action.optInt("seconds", 60))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "navigate" -> context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=${Uri.encode(action.optString("place"))}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "search" -> context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(action.optString("query"))}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_url" -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.optString("url")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "open_settings" -> context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "wifi" -> context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "bluetooth" -> context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "flashlight" -> {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val intent = Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_CAMERA_ACTION, action.toString())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(intent)
                    } else {
                        JarvisAccessibilityService.instance?.setFlashlight(action.optBoolean("enabled", true))
                            ?: notify("Enable Screen Control to control the flashlight.")
                    }
                }
                "volume" -> changeVolume(context, action.optString("direction"))
                "brightness" -> setBrightness(context, action.optInt("level", -1))
                "battery" -> {
                    val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = battery?.getIntExtra("level", -1) ?: -1
                    notify(if (level >= 0) "Battery is at $level%." else "Battery level is unavailable.")
                }
                "reminder" -> scheduleReminder(context, action.optString("text"), action.optLong("minutes", 1))
                "routine" -> runRoutine(context, action.optString("name"), notify)
                "notes" -> {
                    val operation = action.optString("op", "show")
                    if (operation == "save" && action.optString("text").isNotBlank()) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                ChatDatabase.get(context).chatDao().insertNote(UserNote(text = action.optString("text")))
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { notify("Saved that note.") }
                            } catch (error: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    notify(error.message ?: "Could not save the note.")
                                }
                            }
                        }
                    } else if (operation == "read") {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                val note = ChatDatabase.get(context).chatDao().notes().firstOrNull()
                                    ?: throw IllegalStateException("There are no saved notes.")
                                val intent = Intent(context, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_SPEAK_TEXT, note.text)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                context.startActivity(intent)
                            } catch (error: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    notify(error.message ?: "Could not read the note.")
                                }
                            }
                        }
                    } else {
                        val intent = Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_SHOW_NOTES, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(intent)
                    }
                }
                else -> notify("Unsupported action: $type")
            }
        } catch (error: Exception) {
            notify(error.message ?: "Could not perform $type.")
        }
    }

    private fun openApp(context: Context, label: String) {
        val pm = context.packageManager
        val target = pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().equals(label, ignoreCase = true)
        } ?: throw IllegalArgumentException("App not found: $label")
        val intent = pm.getLaunchIntentForPackage(target.packageName)
            ?: throw IllegalStateException("This app cannot be opened.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun searchYoutube(context: Context, query: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
        intent.setPackage("com.google.android.youtube")
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun searchMusic(context: Context, query: String) {
        val uri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.youtube.music")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val spotify = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(spotify)
            } catch (_: Exception) {
                searchYoutube(context, query)
            }
        }
    }

    private fun media(context: Context, command: String, notify: (String) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (command) {
            "volume_up" -> audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "play", "pause", "next", "prev" -> {
                val key = when (command) {
                    "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    else -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                }
                val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key)
                val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key)
                audio.dispatchMediaKeyEvent(down)
                audio.dispatchMediaKeyEvent(up)
            }
            else -> notify("Unknown media command: $command")
        }
    }

    private fun whatsapp(context: Context, name: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Message $name on WhatsApp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun confirmSensitiveAction(context: Context, action: JSONObject) {
        context.startActivity(Intent(context, ActionConfirmationActivity::class.java)
            .putExtra("action", action.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun requestGameStart(context: Context, action: JSONObject) {
        if (AppPrefs.bool(context, "game_warning_shown")) {
            val intent = Intent(context, GameAgentService::class.java)
                .putExtra("game", action.optString("game")).putExtra("goal", action.optString("goal"))
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            return
        }
        if (context is Activity) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Game automation")
                .setMessage("Use only in single-player or sandbox games. Many online games ban automation. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    AppPrefs.putBool(context, "game_warning_shown", true)
                    requestGameStart(context, action)
                }.show()
        } else {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_GAME_ACTION, action.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun changeVolume(context: Context, direction: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (direction == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI)
    }

    private fun setBrightness(context: Context, level: Int) {
        if (level !in 0..255) {
            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!Settings.System.canWrite(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
    }

    private fun scheduleReminder(context: Context, text: String, minutes: Long) {
        val data = androidx.work.workDataOf("text" to text)
        val request = androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(data).setInitialDelay(minutes.coerceAtLeast(1), java.util.concurrent.TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(context).enqueue(request)
    }

    private fun runRoutine(context: Context, name: String, notify: (String) -> Unit) {
        val routine = AppPrefs.get(context, "routine_${name.lowercase()}")
        if (routine.isBlank()) {
            notify("No routine named $name is saved.")
            return
        }
        routine.split("\n").filter { it.isNotBlank() }.forEach { line ->
            try {
                execute(context, JSONObject(line), notify)
            } catch (error: Exception) {
                notify(error.message ?: "A saved routine step could not be run.")
            }
        }
    }
}

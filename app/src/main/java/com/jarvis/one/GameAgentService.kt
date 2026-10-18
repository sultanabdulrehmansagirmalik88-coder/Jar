package com.jarvis.one

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class GameAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing game agent"))
        isRunning = true
        JarvisAccessibilityService.instance?.setGameRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            return START_NOT_STICKY
        }
        val game = intent?.getStringExtra("game").orEmpty()
        val goal = intent?.getStringExtra("goal").orEmpty()
        if (game.isNotBlank() && goal.isNotBlank() && agentJob?.isActive != true) {
            updateNotification("Planning: $goal")
            agentJob = scope.launch {
                try {
                    val plan = GameBrain.makePlan(this@GameAgentService, game, goal)
                    GameAutomation(this@GameAgentService).run(plan)
                    stopSelf()
                } catch (_: CancellationException) {
                    stopSelf()
                } catch (error: Exception) {
                    updateNotification(error.message ?: "Game agent failed")
                    kotlinx.coroutines.delay(4000)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopAgent() {
        agentJob?.cancel()
        agentJob = null
        stopSelf()
    }

    override fun onDestroy() {
        agentJob?.cancel()
        scope.cancel()
        isRunning = false
        JarvisAccessibilityService.instance?.setGameRunning(false)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game Agent", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val stopIntent = Intent(this, GameAgentService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 41, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis Game Agent")
            .setContentText(message)
            .setOngoing(true)
            .addAction(0, "Stop", pending)
            .build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.one.GAME_STOP"
        private const val CHANNEL_ID = "jarvis_game_agent"
        private const val NOTIFICATION_ID = 520
        @Volatile var isRunning: Boolean = false
            private set
    }
}

object GameBrain {
    suspend fun makePlan(context: android.content.Context, game: String, goal: String): JSONArray {
        val prompt = "You plan safe single-player game macros for $game. Goal: $goal. Return ONLY a JSON array of objects. Supported steps: {\"type\":\"tap\",\"x\":number,\"y\":number}, {\"type\":\"swipe\",\"x1\":number,\"y1\":number,\"x2\":number,\"y2\":number,\"ms\":number}, {\"type\":\"wait\",\"ms\":number}, {\"type\":\"repeat\",\"n\":number,\"steps\":[...]}. Coordinates are screen pixels; use short steps and pauses. Limit plan to 30 top-level steps."
        val reply = GroqClient.complete(context, listOf("user" to "Create the macro plan."), prompt).text
        val json = reply.substringAfter("```json", reply).substringBefore("```").trim()
        val start = json.indexOf('[')
        val end = json.lastIndexOf(']')
        if (start < 0 || end < start) throw IllegalArgumentException("The model returned an invalid game plan.")
        return JSONArray(json.substring(start, end + 1))
    }
}

class GameAutomation(private val context: android.content.Context) {
    private var executedSteps = 0

    suspend fun run(plan: JSONArray) {
        if (JarvisAccessibilityService.instance == null) {
            throw IllegalStateException("Enable Screen Control before starting the game agent.")
        }
        for (index in 0 until plan.length()) {
            if (!GameAgentService.isRunning) return
            runStep(plan.optJSONObject(index) ?: continue, 0)
        }
    }

    private suspend fun runStep(step: JSONObject, depth: Int) {
        if (depth > 4 || !GameAgentService.isRunning) return
        executedSteps++
        if (executedSteps > 500) throw IllegalStateException("Game macro reached the 500-step safety limit.")
        val service = JarvisAccessibilityService.instance ?: return
        when (step.optString("type")) {
            "tap" -> service.performTap(step.optInt("x"), step.optInt("y"))
            "swipe" -> service.performSwipe(step.optInt("x1"), step.optInt("y1"),
                step.optInt("x2"), step.optInt("y2"), step.optLong("ms", 400))
            "wait" -> delay(step.optLong("ms", 500).coerceIn(100, 10000))
            "repeat" -> {
                val count = step.optInt("n", 1).coerceIn(1, 10)
                val steps = step.optJSONArray("steps") ?: JSONArray()
                repeat(count) {
                    if (!GameAgentService.isRunning) return
                    for (index in 0 until steps.length()) runStep(steps.optJSONObject(index) ?: continue, depth + 1)
                }
            }
        }
        delay(250)
    }
}

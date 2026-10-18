package com.jarvis.one

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiReply(val text: String, val action: JSONObject? = null)

object GroqClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val actionPattern = Regex("```action\\s*(\\{.*?})\\s*```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parseReply(raw: String): AiReply {
        val match = actionPattern.find(raw) ?: return AiReply(raw.trim())
        val action = runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
        if (action == null) return AiReply(raw.trim())
        return AiReply(raw.replaceRange(match.range, "").trim(), action)
    }

    fun visibleStreamingText(raw: String): String {
        val actionStart = Regex("```action", RegexOption.IGNORE_CASE).find(raw) ?: return raw
        val closingFence = raw.indexOf("```", actionStart.range.last + 1)
        return if (closingFence < 0) raw.substring(0, actionStart.range.first).trim()
        else parseReply(raw).text
    }

    suspend fun complete(
        context: Context,
        messages: List<Pair<String, String>>,
        systemPrompt: String = AppPrefs.systemPrompt(context)
    ): AiReply = withContext(Dispatchers.IO) {
        val key = AppPrefs.get(context, "api_key").trim()
        if (key.isEmpty()) throw IOException("Enter an API key in Settings.")
        val endpoint = if (key.startsWith("xai-")) {
            "https://api.x.ai/v1/chat/completions"
        } else {
            "https://api.groq.com/openai/v1/chat/completions"
        }
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.takeLast(20).forEach { (role, content) ->
            requestMessages.put(JSONObject().put("role", role).put("content", content))
        }
        val requestBody = JSONObject()
            .put("model", AppPrefs.get(context, "model", AppPrefs.DEFAULT_MODEL))
            .put("messages", requestMessages)
            .put("temperature", 0.6)
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(requestBody).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: body.ifBlank { response.message }
                throw IOException("API error ${response.code}: $detail")
            }
            val text = JSONObject(body).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").optString("content")
            parseReply(text)
        }
    }

    suspend fun stream(
        context: Context,
        messages: List<Pair<String, String>>,
        onText: (String) -> Unit,
        systemPrompt: String = AppPrefs.systemPrompt(context)
    ): AiReply = withContext(Dispatchers.IO) {
        val key = AppPrefs.get(context, "api_key").trim()
        if (key.isEmpty()) throw IOException("Enter an API key in Settings.")
        val endpoint = if (key.startsWith("xai-")) "https://api.x.ai/v1/chat/completions"
        else "https://api.groq.com/openai/v1/chat/completions"
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.takeLast(20).forEach { (role, content) ->
            requestMessages.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject().put("model", AppPrefs.get(context, "model", AppPrefs.DEFAULT_MODEL))
            .put("messages", requestMessages).put("temperature", 0.6).put("stream", true)
            .toString().toRequestBody(jsonType)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(body).build()
        val raw = StringBuilder()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw IOException("API error ${response.code}: ${details.ifBlank { response.message }}")
            }
            val source = response.body?.source() ?: throw IOException("API returned an empty response.")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val content = runCatching {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("delta").optString("content")
                }.getOrNull().orEmpty()
                if (content.isNotEmpty()) {
                    raw.append(content)
                    onText(raw.toString())
                }
            }
        }
        parseReply(raw.toString())
    }

    suspend fun test(context: Context): String {
        complete(context, listOf("user" to "Reply with OK."), "Reply with OK.")
        return "OK"
    }

    suspend fun answerImage(context: Context, imageDataUrl: String, question: String): String = withContext(Dispatchers.IO) {
        val key = AppPrefs.get(context, "api_key").trim()
        if (key.isEmpty()) throw IOException("Enter an API key in Settings.")
        if (key.startsWith("xai-")) throw IOException("Image questions require a Groq vision model.")
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AppPrefs.systemPrompt(context)))
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", AppPrefs.get(context, "vision_model", "meta-llama/llama-4-scout-17b-16e-instruct"))
            .put("messages", messages).toString().toRequestBody(jsonType)
        client.newCall(Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key").post(body).build()).execute().use { response ->
            val result = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("API error ${response.code}: $result")
            JSONObject(result).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        }
    }
}

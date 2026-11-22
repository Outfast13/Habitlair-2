package com.habitlair.app.ai

import android.os.SystemClock
import com.habitlair.app.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.*

data class AiConfig(
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val temperature: Float
)

class AiException(val kind: Kind, val httpCode: Int?, message: String) : Exception(message) {
    enum class Kind(val label: String) {
        NO_KEY("No API key"), AUTH("Authentication failed"), QUOTA("Quota / rate limit"),
        NETWORK("Network error"), SERVER("Provider server error"), BAD_RESPONSE("Unusable response"),
        CLIENT("Request rejected")
    }

    fun describe(): String = buildString {
        append(kind.label)
        httpCode?.let { append(" (HTTP $it)") }
        append(": ").append(message)
    }
}

data class AiResult(val text: String, val model: String, val ms: Long)

data class ChatTurn(val role: String, val text: String)

/** Dependency-free HTTP client for Gemini and OpenAI-style chat endpoints. */
class AiClient {
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun chat(cfg: AiConfig, system: String, user: String, json: Boolean = false): AiResult =
        chatTurns(cfg, system, listOf(ChatTurn("user", user)), json)

    /** Merges consecutive same-role turns and makes sure the conversation starts with the user. */
    private fun normalize(turns: List<ChatTurn>): List<ChatTurn> {
        val out = mutableListOf<ChatTurn>()
        for (t in turns) {
            if (t.text.isBlank()) continue
            if (out.isEmpty() && t.role != "user") continue
            if (out.isNotEmpty() && out.last().role == t.role) out[out.size - 1] = ChatTurn(t.role, out.last().text + "\n\n" + t.text)
            else out += t
        }
        return out
    }

    suspend fun chatTurns(cfg: AiConfig, system: String, rawTurns: List<ChatTurn>, json: Boolean = false): AiResult =
        withContext(Dispatchers.IO) {
            val turns = normalize(rawTurns)
            if (cfg.apiKey.isBlank() && cfg.provider != Provider.CUSTOM)
                throw AiException(AiException.Kind.NO_KEY, null, "Add an API key in Settings → AI Configuration.")
            if (cfg.model.isBlank())
                throw AiException(AiException.Kind.CLIENT, null, "Model ID is empty.")
            val base = cfg.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) throw AiException(AiException.Kind.CLIENT, null, "Base URL is empty.")

            val gemini = cfg.provider == Provider.GEMINI
            val url = if (gemini) "$base/models/${cfg.model.removePrefix("models/")}:generateContent" else "$base/chat/completions"
            val headers = buildMap {
                if (gemini) put("x-goog-api-key", cfg.apiKey)
                else if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey}")
                if (cfg.provider == Provider.OPENROUTER) { put("HTTP-Referer", "https://habitlair.app"); put("X-Title", "HabitLair") }
            }

            val start = SystemClock.elapsedRealtime()
            var withTemp = true
            var (code, text) = send(url, headers, body(cfg, system, turns, json, withTemp))
            if (code == 400 && text.contains("temperature", ignoreCase = true)) {
                withTemp = false
                val r = send(url, headers, body(cfg, system, turns, json, withTemp)); code = r.first; text = r.second
            }
            val ms = SystemClock.elapsedRealtime() - start
            if (code !in 200..299) throw httpError(code, text)
            parse(cfg, text, ms)
        }

    private fun body(cfg: AiConfig, system: String, turns: List<ChatTurn>, json: Boolean, withTemp: Boolean): String =
        if (cfg.provider == Provider.GEMINI) buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
            put("contents", buildJsonArray {
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.role == "user") "user" else "model")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", t.text) }) })
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                if (withTemp) put("temperature", cfg.temperature)
                if (json) put("responseMimeType", "application/json")
            })
        }.toString()
        else buildJsonObject {
            put("model", cfg.model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                turns.forEach { t -> add(buildJsonObject { put("role", if (t.role == "user") "user" else "assistant"); put("content", t.text) }) }
            })
            if (withTemp) put("temperature", cfg.temperature)
            if (json && cfg.provider == Provider.OPENAI) put("response_format", buildJsonObject { put("type", "json_object") })
        }.toString()

    private fun send(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            return code to text
        } catch (e: MalformedURLException) {
            throw AiException(AiException.Kind.CLIENT, null, "Invalid base URL: ${e.message}")
        } catch (e: UnknownHostException) {
            throw AiException(AiException.Kind.NETWORK, null, "Can't resolve host. Check your connection and base URL.")
        } catch (e: SocketTimeoutException) {
            throw AiException(AiException.Kind.NETWORK, null, "Timed out waiting for the provider.")
        } catch (e: ConnectException) {
            throw AiException(AiException.Kind.NETWORK, null, "Connection refused: ${e.message}")
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, null, e.message ?: "I/O error")
        }
    }

    private fun httpError(code: Int, body: String): AiException {
        val msg = runCatching {
            val e = parser.parseToJsonElement(body).jsonObject["error"]
            when (e) {
                is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> e.contentOrNull
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.trim().take(300).ifBlank { "Empty response body" }
        val low = msg.lowercase()
        val kind = when {
            code == 401 || code == 403 -> AiException.Kind.AUTH
            code == 429 || code == 402 -> AiException.Kind.QUOTA
            code == 400 && ("api key" in low || "api_key" in low) -> AiException.Kind.AUTH
            code in 500..599 -> AiException.Kind.SERVER
            else -> AiException.Kind.CLIENT
        }
        val hint = if (code == 404) " (check the model ID and base URL)" else ""
        return AiException(kind, code, msg + hint)
    }

    private fun parse(cfg: AiConfig, raw: String, ms: Long): AiResult {
        try {
            val root = parser.parseToJsonElement(raw).jsonObject
            if (cfg.provider == Provider.GEMINI) {
                val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val text = cand?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }?.joinToString("") ?: ""
                if (text.isBlank()) {
                    val why = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                        ?: cand?.get("finishReason")?.jsonPrimitive?.contentOrNull ?: "no text returned"
                    throw AiException(AiException.Kind.BAD_RESPONSE, null, "Gemini returned no content ($why).")
                }
                return AiResult(text, root["modelVersion"]?.jsonPrimitive?.contentOrNull ?: cfg.model, ms)
            }
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
            val text = when (content) {
                is JsonPrimitive -> content.contentOrNull ?: ""
                is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
                else -> ""
            }
            if (text.isBlank()) throw AiException(AiException.Kind.BAD_RESPONSE, null, "The model returned an empty message.")
            return AiResult(text, root["model"]?.jsonPrimitive?.contentOrNull ?: cfg.model, ms)
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(AiException.Kind.BAD_RESPONSE, null, "Couldn't read the provider response: ${e.message?.take(120)}")
        }
    }
}

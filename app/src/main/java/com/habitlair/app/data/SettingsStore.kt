package com.habitlair.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.io.IOException

enum class Provider(
    val label: String,
    val defaultBase: String,
    val defaultModel: String,
    val models: List<String>
) {
    GEMINI(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
        listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3.5-flash", "gemini-flash-latest", "gemini-flash-lite-latest")
    ),
    OPENAI(
        "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")
    ),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini",
        listOf("openai/gpt-4o-mini", "google/gemini-2.5-flash", "anthropic/claude-sonnet-4.5", "meta-llama/llama-3.3-70b-instruct:free", "deepseek/deepseek-chat-v3.1:free")
    ),
    CUSTOM("Custom (OpenAI-compatible)", "http://localhost:11434/v1", "", emptyList())
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED Black") }

enum class Accent(val label: String, val argb: Long) {
    CRIMSON("Crimson", 0xFFE0415F), VIOLET("Violet", 0xFFA66DF0), GREEN("Green", 0xFF7CB342),
    OCEAN("Ocean", 0xFF3F8CF2), AMBER("Amber", 0xFFF2A33A)
}

const val DEFAULT_PROMPT =
    "You are HabitLair Coach: a warm, concise, practical habit and productivity coach. " +
        "Prefer small, realistic steps over big overhauls, and keep suggestions specific and schedulable."

@Serializable
data class AppSettings(
    val provider: Provider = Provider.GEMINI,
    val models: Map<Provider, String> = emptyMap(),
    val customBaseUrl: String = "",
    val systemPrompt: String = DEFAULT_PROMPT,
    val temperature: Float = 0.7f,
    val shareContext: Boolean = true,

    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accent: Accent = Accent.CRIMSON,
    val animations: Boolean = true,
    val compact: Boolean = false,

    val remindersEnabled: Boolean = true,
    val defaultReminderMinutes: Int = 9 * 60,
    val sound: Boolean = true,
    val vibration: Boolean = true,

    val focusMin: Int = 25,
    val breakMin: Int = 5,
    val longBreakMin: Int = 15,
    val cyclesBeforeLong: Int = 4
) {
    fun modelFor(p: Provider): String = models[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel
    val baseUrl: String
        get() = if (provider == Provider.CUSTOM) customBaseUrl.ifBlank { Provider.CUSTOM.defaultBase } else provider.defaultBase
}

private val Context.dataStore by preferencesDataStore("habitlair_settings")

class SettingsStore(ctx: Context) {
    private val ds = ctx.applicationContext.dataStore

    val flow: Flow<AppSettings> = ds.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()
    suspend fun update(t: (AppSettings) -> AppSettings) { ds.edit { p -> p.write(t(p.toSettings())) } }
    suspend fun replace(s: AppSettings) = update { s }

    private object K {
        val provider = stringPreferencesKey("provider")
        val baseUrl = stringPreferencesKey("custom_base")
        val prompt = stringPreferencesKey("prompt")
        val temp = floatPreferencesKey("temp")
        val share = booleanPreferencesKey("share_ctx")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = stringPreferencesKey("accent")
        val anim = booleanPreferencesKey("anim")
        val compact = booleanPreferencesKey("compact")
        val reminders = booleanPreferencesKey("reminders")
        val defTime = intPreferencesKey("def_time")
        val sound = booleanPreferencesKey("sound")
        val vibe = booleanPreferencesKey("vibe")
        val focus = intPreferencesKey("focus")
        val brk = intPreferencesKey("break")
        val longBrk = intPreferencesKey("long_break")
        val cycles = intPreferencesKey("cycles")
        fun model(p: Provider) = stringPreferencesKey("model_${p.name}")
    }

    private inline fun <reified E : Enum<E>> Preferences.enumOf(k: Preferences.Key<String>, d: E): E =
        this[k]?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: d

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = enumOf(K.provider, d.provider),
            models = Provider.entries.associateWith { this[K.model(it)] ?: it.defaultModel },
            customBaseUrl = this[K.baseUrl] ?: d.customBaseUrl,
            systemPrompt = this[K.prompt] ?: d.systemPrompt,
            temperature = this[K.temp] ?: d.temperature,
            shareContext = this[K.share] ?: d.shareContext,
            themeMode = enumOf(K.theme, d.themeMode),
            dynamicColor = this[K.dynamic] ?: d.dynamicColor,
            accent = enumOf(K.accent, d.accent),
            animations = this[K.anim] ?: d.animations,
            compact = this[K.compact] ?: d.compact,
            remindersEnabled = this[K.reminders] ?: d.remindersEnabled,
            defaultReminderMinutes = this[K.defTime] ?: d.defaultReminderMinutes,
            sound = this[K.sound] ?: d.sound,
            vibration = this[K.vibe] ?: d.vibration,
            focusMin = this[K.focus] ?: d.focusMin,
            breakMin = this[K.brk] ?: d.breakMin,
            longBreakMin = this[K.longBrk] ?: d.longBreakMin,
            cyclesBeforeLong = this[K.cycles] ?: d.cyclesBeforeLong
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.provider] = s.provider.name
        Provider.entries.forEach { this[K.model(it)] = s.modelFor(it) }
        this[K.baseUrl] = s.customBaseUrl
        this[K.prompt] = s.systemPrompt
        this[K.temp] = s.temperature
        this[K.share] = s.shareContext
        this[K.theme] = s.themeMode.name
        this[K.dynamic] = s.dynamicColor
        this[K.accent] = s.accent.name
        this[K.anim] = s.animations
        this[K.compact] = s.compact
        this[K.reminders] = s.remindersEnabled
        this[K.defTime] = s.defaultReminderMinutes
        this[K.sound] = s.sound
        this[K.vibe] = s.vibration
        this[K.focus] = s.focusMin
        this[K.brk] = s.breakMin
        this[K.longBrk] = s.longBreakMin
        this[K.cycles] = s.cyclesBeforeLong
    }
}

/** API keys live only here: AES-256 encrypted SharedPreferences backed by the Android Keystore. */
class SecureKeyStore(ctx: Context) {
    private val prefs: SharedPreferences = try {
        create(ctx)
    } catch (e: Exception) {
        ctx.deleteSharedPreferences(FILE)
        create(ctx)
    }

    private fun create(ctx: Context): SharedPreferences = EncryptedSharedPreferences.create(
        ctx, FILE,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(p: Provider): String = prefs.getString("key_${p.name}", "") ?: ""
    fun set(p: Provider, v: String) {
        val e = prefs.edit()
        if (v.isBlank()) e.remove("key_${p.name}") else e.putString("key_${p.name}", v.trim())
        e.apply()
    }

    private companion object { const val FILE = "habitlair_secure_keys" }
}

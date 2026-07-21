package com.konashevich.pressscribe.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE].toEnumOrDefault(ThemeMode.AUTO),
            fontSize = prefs[Keys.FONT_SIZE].toEnumOrDefault(FontSizeOption.MEDIUM),
            listenMode = prefs[Keys.LISTEN_MODE].toEnumOrDefault(ListenMode.HOLD),
            volumeButtonMode = prefs[Keys.VOLUME_BUTTON_MODE].toEnumOrDefault(VolumeButtonMode.HOLD_ANY),
            transcriptionService = prefs[Keys.TRANSCRIPTION_SERVICE]
                .toEnumOrDefault(TranscriptionService.GEMINI),
            geminiApiKey = prefs[Keys.GEMINI_API_KEY].orEmpty().trim(),
            geminiModel = normalizeGeminiModel(prefs[Keys.GEMINI_MODEL].orEmpty()),
            polishPrompt = prefs[Keys.POLISH_PROMPT].orEmpty().ifBlank { DEFAULT_POLISH_PROMPT },
            translatePolishPrompt = prefs[Keys.TRANSLATE_POLISH_PROMPT].orEmpty()
                .ifBlank { DEFAULT_TRANSLATE_POLISH_PROMPT },
            translateLanguageCode = normalizeTranslateLanguageCode(
                prefs[Keys.TRANSLATE_LANGUAGE_CODE].orEmpty(),
            ),
            serverScheme = prefs[Keys.SERVER_SCHEME].toEnumOrDefault(ServerScheme.HTTP),
            serverHost = prefs[Keys.SERVER_HOST].orEmpty(),
            serverPort = prefs[Keys.SERVER_PORT].orEmpty().ifBlank { "8711" },
            serverPath = prefs[Keys.SERVER_PATH].orEmpty().ifBlank { "/transcribe" },
            serverTimeoutSeconds = prefs[Keys.SERVER_TIMEOUT_SECONDS] ?: 360,
            vibrationDurationMs = prefs[Keys.VIBRATION_DURATION_MS] ?: DEFAULT_VIBRATION_DURATION_MS,
            autoSaveNotes = prefs[Keys.AUTO_SAVE_NOTES] ?: true,
        )
    }

    suspend fun updateThemeMode(value: ThemeMode) = editString(Keys.THEME_MODE, value.name)

    suspend fun updateFontSize(value: FontSizeOption) = editString(Keys.FONT_SIZE, value.name)

    suspend fun updateListenMode(value: ListenMode) = editString(Keys.LISTEN_MODE, value.name)

    suspend fun updateVolumeButtonMode(value: VolumeButtonMode) =
        editString(Keys.VOLUME_BUTTON_MODE, value.name)

    suspend fun updateTranscriptionService(value: TranscriptionService) =
        editString(Keys.TRANSCRIPTION_SERVICE, value.name)

    suspend fun updateGeminiApiKey(value: String) = editString(Keys.GEMINI_API_KEY, value.trim())

    suspend fun updateGeminiModel(value: String) =
        editString(Keys.GEMINI_MODEL, normalizeGeminiModel(value))

    suspend fun updatePolishPrompt(value: String) = editString(Keys.POLISH_PROMPT, value)

    suspend fun updateTranslatePolishPrompt(value: String) =
        editString(Keys.TRANSLATE_POLISH_PROMPT, value)

    suspend fun updateTranslateLanguageCode(value: String) =
        editString(Keys.TRANSLATE_LANGUAGE_CODE, normalizeTranslateLanguageCode(value))

    suspend fun updateServerScheme(value: ServerScheme) = editString(Keys.SERVER_SCHEME, value.name)

    suspend fun updateServerHost(value: String) = editString(Keys.SERVER_HOST, value)

    suspend fun updateServerPort(value: String) = editString(Keys.SERVER_PORT, value)

    suspend fun updateServerPath(value: String) = editString(Keys.SERVER_PATH, value)

    suspend fun updateServerTimeoutSeconds(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_TIMEOUT_SECONDS] = value.coerceAtLeast(1)
        }
    }

    suspend fun updateVibrationDurationMs(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VIBRATION_DURATION_MS] = value.coerceAtLeast(0)
        }
    }

    suspend fun updateAutoSaveNotes(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_SAVE_NOTES] = value
        }
    }

    suspend fun importSettings(patch: SettingsImportPatch) {
        context.dataStore.edit { prefs ->
            patch.themeMode?.let { prefs[Keys.THEME_MODE] = it.name }
            patch.fontSize?.let { prefs[Keys.FONT_SIZE] = it.name }
            patch.listenMode?.let { prefs[Keys.LISTEN_MODE] = it.name }
            patch.volumeButtonMode?.let { prefs[Keys.VOLUME_BUTTON_MODE] = it.name }
            patch.transcriptionService?.let { prefs[Keys.TRANSCRIPTION_SERVICE] = it.name }
            patch.geminiApiKey?.let { prefs[Keys.GEMINI_API_KEY] = it.trim() }
            patch.geminiModel?.let { prefs[Keys.GEMINI_MODEL] = normalizeGeminiModel(it) }
            patch.polishPrompt?.let { prefs[Keys.POLISH_PROMPT] = it }
            patch.translatePolishPrompt?.let { prefs[Keys.TRANSLATE_POLISH_PROMPT] = it }
            patch.translateLanguageCode?.let {
                prefs[Keys.TRANSLATE_LANGUAGE_CODE] = normalizeTranslateLanguageCode(it)
            }
            patch.serverScheme?.let { prefs[Keys.SERVER_SCHEME] = it.name }
            patch.serverHost?.let { prefs[Keys.SERVER_HOST] = it }
            patch.serverPort?.let { prefs[Keys.SERVER_PORT] = it }
            patch.serverPath?.let { prefs[Keys.SERVER_PATH] = it }
            patch.serverTimeoutSeconds?.let { prefs[Keys.SERVER_TIMEOUT_SECONDS] = it.coerceAtLeast(1) }
            patch.vibrationDurationMs?.let { prefs[Keys.VIBRATION_DURATION_MS] = it.coerceAtLeast(0) }
        }
    }

    private suspend fun editString(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val LISTEN_MODE = stringPreferencesKey("listen_mode")
        val VOLUME_BUTTON_MODE = stringPreferencesKey("volume_button_mode")
        val TRANSCRIPTION_SERVICE = stringPreferencesKey("transcription_service")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val POLISH_PROMPT = stringPreferencesKey("polish_prompt")
        val TRANSLATE_POLISH_PROMPT = stringPreferencesKey("translate_polish_prompt")
        val TRANSLATE_LANGUAGE_CODE = stringPreferencesKey("translate_language_code")
        val SERVER_SCHEME = stringPreferencesKey("server_scheme")
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = stringPreferencesKey("server_port")
        val SERVER_PATH = stringPreferencesKey("server_path")
        val SERVER_TIMEOUT_SECONDS = intPreferencesKey("server_timeout_seconds")
        val VIBRATION_DURATION_MS = intPreferencesKey("vibration_duration_ms")
        val AUTO_SAVE_NOTES = booleanPreferencesKey("auto_save_notes")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    val value = this ?: return default
    return enumValues<T>().firstOrNull { it.name == value } ?: default
}

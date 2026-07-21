package com.konashevich.pressscribe.data

import org.json.JSONObject
import java.net.URI

data class SettingsImportPatch(
    val themeMode: ThemeMode? = null,
    val fontSize: FontSizeOption? = null,
    val listenMode: ListenMode? = null,
    val volumeButtonMode: VolumeButtonMode? = null,
    val transcriptionService: TranscriptionService? = null,
    val geminiApiKey: String? = null,
    val geminiModel: String? = null,
    val polishPrompt: String? = null,
    val serverScheme: ServerScheme? = null,
    val serverHost: String? = null,
    val serverPort: String? = null,
    val serverPath: String? = null,
    val serverTimeoutSeconds: Int? = null,
    val vibrationDurationMs: Int? = null,
) {
    fun hasAnyValue(): Boolean {
        return themeMode != null ||
            fontSize != null ||
            listenMode != null ||
            volumeButtonMode != null ||
            transcriptionService != null ||
            geminiApiKey != null ||
            geminiModel != null ||
            polishPrompt != null ||
            serverScheme != null ||
            serverHost != null ||
            serverPort != null ||
            serverPath != null ||
            serverTimeoutSeconds != null ||
            vibrationDurationMs != null
    }
}

fun parseDesktopSettingsImport(content: String): SettingsImportPatch {
    val root = JSONObject(content)
    val importedAsrUrl = root.optImportedString("qwen_asr_url")
        ?.takeIf { it.isNotBlank() }
        ?.let(::parseSelfHostedUrl)

    return SettingsImportPatch(
        themeMode = root.optImportedString("theme")?.toThemeMode(),
        fontSize = root.optImportedValue("font_size").toFontSizeOption(),
        listenMode = root.optImportedString("listen_mode")?.toListenMode(),
        volumeButtonMode = root.optImportedString("volume_button_mode")?.toVolumeButtonMode(),
        transcriptionService = root.optImportedString("transcription_service")?.toTranscriptionService(),
        geminiApiKey = root.optImportedString("api_key"),
        geminiModel = root.optImportedString("gemini_model")?.let(::normalizeGeminiModel),
        polishPrompt = root.optImportedString("system_prompt"),
        serverScheme = importedAsrUrl?.scheme,
        serverHost = importedAsrUrl?.host,
        serverPort = importedAsrUrl?.port,
        serverPath = importedAsrUrl?.path,
        serverTimeoutSeconds = root.optImportedValue("qwen_asr_timeout_seconds").toPositiveInt(),
        vibrationDurationMs = root.optImportedValue("vibration_duration_ms").toNonNegativeInt()
            ?: root.optImportedValue("haptic_duration_ms").toNonNegativeInt(),
    )
}

private fun JSONObject.optImportedString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return optString(key)
}

private fun JSONObject.optImportedValue(key: String): Any? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return opt(key)
}

private fun String.toThemeMode(): ThemeMode? {
    return when (trim().lowercase()) {
        "auto", "system", "follow device", "follow-device", "device" -> ThemeMode.AUTO
        "dark" -> ThemeMode.DARK
        "light" -> ThemeMode.LIGHT
        else -> null
    }
}

private fun Any?.toFontSizeOption(): FontSizeOption? {
    val size = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    } ?: return null

    return when {
        size <= 10 -> FontSizeOption.SMALL
        size <= 11 -> FontSizeOption.MEDIUM
        else -> FontSizeOption.LARGE
    }
}

private fun String.toListenMode(): ListenMode? {
    val normalized = trim().lowercase()
    return when (normalized) {
        "click and hold", "press and hold", "hold" -> ListenMode.HOLD
        "click and stick", "tap to toggle", "toggle" -> ListenMode.TOGGLE
        else -> null
    }
}

private fun String.toVolumeButtonMode(): VolumeButtonMode? {
    val normalized = trim().lowercase()
    return when (normalized) {
        "hold_any", "hold-any", "hold either", "hold_either", "hold either volume button",
        "press and hold either volume button", "either", "either volume" -> VolumeButtonMode.HOLD_ANY

        "toggle_split", "toggle-split", "split", "split toggle", "vol+ start, vol- stop",
        "volume up start volume down stop", "volume up starts, volume down stops" ->
            VolumeButtonMode.TOGGLE_SPLIT

        else -> null
    }
}

private fun String.toTranscriptionService(): TranscriptionService? {
    val normalized = trim().lowercase()
    return when {
        normalized.contains("gemini") || normalized.contains("google") -> TranscriptionService.GEMINI
        normalized.contains("qwen") || normalized.contains("self-hosted") || normalized.contains("asr") ->
            TranscriptionService.SELF_HOSTED
        else -> null
    }
}

private fun Any?.toPositiveInt(): Int? {
    val value = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    } ?: return null

    return value.takeIf { it > 0 }
}

private fun Any?.toNonNegativeInt(): Int? {
    val value = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    } ?: return null

    return value.takeIf { it >= 0 }
}

private fun parseSelfHostedUrl(rawUrl: String): ImportedSelfHostedUrl? {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty()) {
        return null
    }

    val scheme = when (uri.scheme?.lowercase()) {
        "https" -> ServerScheme.HTTPS
        else -> ServerScheme.HTTP
    }
    val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"

    return ImportedSelfHostedUrl(
        scheme = scheme,
        host = host,
        port = uri.port.takeIf { it >= 0 }?.toString().orEmpty(),
        path = path,
    )
}

private data class ImportedSelfHostedUrl(
    val scheme: ServerScheme,
    val host: String,
    val port: String,
    val path: String,
)

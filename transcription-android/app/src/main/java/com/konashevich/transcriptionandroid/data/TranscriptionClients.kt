package com.konashevich.pressscribe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

class GeminiApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build(),
) {

    suspend fun transcribeAudio(
        file: File,
        mimeType: String,
        settings: AppSettings,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("Gemini API key is not configured.")
        }
        val model = settings.resolvedGeminiModel()

        val uploadedFile = uploadFile(file, mimeType, apiKey)
        try {
            val payload = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "text",
                                        "Transcribe this audio. Return only the spoken words as plain text.",
                                    ),
                                )
                                .put(
                                    JSONObject().put(
                                        "file_data",
                                        JSONObject()
                                            .put("mime_type", uploadedFile.mimeType)
                                            .put("file_uri", uploadedFile.uri),
                                    ),
                                ),
                        ),
                    ),
                )

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", jsonMediaType.toString())
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            extractText(executeRequest(request))
                .ifBlank { throw IllegalStateException("Gemini returned an empty transcription.") }
        } finally {
            deleteUploadedFile(uploadedFile.name, apiKey)
        }
    }

    suspend fun polishText(
        text: String,
        settings: AppSettings,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("Gemini API key is not configured.")
        }
        val model = settings.resolvedGeminiModel()

        val payload = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("text", settings.polishPrompt),
                    ),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", text),
                            ),
                        ),
                ),
            )

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", jsonMediaType.toString())
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        extractText(executeRequest(request))
            .ifBlank { throw IllegalStateException("Gemini returned an empty polished result.") }
    }

    suspend fun polishAndTranslateText(
        text: String,
        settings: AppSettings,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("Gemini API key is not configured.")
        }
        val languageCode = settings.translateLanguageCode.trim()
        if (languageCode.isEmpty()) {
            throw IllegalStateException("Translation language is not configured.")
        }
        val model = settings.resolvedGeminiModel()
        val systemPrompt = resolveTranslatePrompt(
            template = settings.translatePolishPrompt,
            languageCode = languageCode,
        )

        val payload = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("text", systemPrompt),
                    ),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", text),
                            ),
                        ),
                ),
            )

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", jsonMediaType.toString())
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        extractText(executeRequest(request))
            .ifBlank { throw IllegalStateException("Gemini returned an empty translation result.") }
    }

    private fun uploadFile(file: File, mimeType: String, apiKey: String): UploadedGeminiFile {
        val startPayload = JSONObject()
            .put(
                "file",
                JSONObject().put("display_name", file.name),
            )

        val startRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .header("Content-Type", jsonMediaType.toString())
            .post(startPayload.toString().toRequestBody(jsonMediaType))
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Gemini file upload init failed: ${response.code} ${response.body?.string().orEmpty()}")
            }
            response.header("X-Goog-Upload-URL")
                ?: response.header("x-goog-upload-url")
                ?: throw IOException("Gemini file upload URL was missing from the response headers.")
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", file.length().toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        val responseBody = executeRequest(uploadRequest)
        val fileJson = JSONObject(responseBody).getJSONObject("file")
        return UploadedGeminiFile(
            name = fileJson.getString("name"),
            uri = fileJson.getString("uri"),
            mimeType = fileJson.optString("mimeType").ifBlank {
                fileJson.optString("mime_type").ifBlank { mimeType }
            },
        )
    }

    private fun deleteUploadedFile(fileName: String, apiKey: String) {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$fileName")
            .header("x-goog-api-key", apiKey)
            .delete()
            .build()

        runCatching {
            client.newCall(request).execute().use { }
        }
    }

    private fun executeRequest(request: Request): String {
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $body")
            }
            body
        }
    }

    private fun extractText(rawJson: String): String {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        val partsText = mutableListOf<String>()

        for (candidateIndex in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(candidateIndex) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (partIndex in 0 until parts.length()) {
                val text = parts.optJSONObject(partIndex)?.optString("text").orEmpty().trim()
                if (text.isNotEmpty()) {
                    partsText += text
                }
            }
        }

        return partsText.joinToString(separator = "\n").trim()
    }
}

class SelfHostedAsrClient(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(6, TimeUnit.MINUTES)
        .build(),
) {

    suspend fun transcribeAudio(
        file: File,
        mimeType: String,
        settings: AppSettings,
    ): String = withContext(Dispatchers.IO) {
        val url = settings.selfHostedUrl()
            ?: throw IllegalStateException("Self-hosted ASR host is not configured.")

        val requestClient = baseClient.newBuilder()
            .callTimeout(settings.serverTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                file.name,
                file.asRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val responseBody = requestClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $bodyText")
            }
            bodyText
        }

        parseTranscription(responseBody)
            .ifBlank { throw IllegalStateException("Self-hosted ASR returned an empty transcription.") }
    }

    private fun parseTranscription(rawJson: String): String {
        val root = JSONObject(rawJson)

        val nestedParsedText = root.optJSONObject("transcription")
            ?.optString("parsed_text")
            .orEmpty()
            .trim()
        if (nestedParsedText.isNotEmpty()) {
            return nestedParsedText
        }

        val flatText = root.optString("text").trim()
        if (flatText.isNotEmpty()) {
            return flatText
        }

        return root.optString("transcription").trim()
    }
}

private data class UploadedGeminiFile(
    val name: String,
    val uri: String,
    val mimeType: String,
)

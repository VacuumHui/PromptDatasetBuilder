package com.promptdatasetbuilder.app.network

import com.promptdatasetbuilder.app.BuildConfig
import com.promptdatasetbuilder.app.data.AppSettings
import com.promptdatasetbuilder.app.data.ImagePage
import com.promptdatasetbuilder.app.data.NetworkDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class CivitaiApiClient {
    private val client = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun loadPage(
        settings: AppSettings,
        nextUrl: String? = null,
        nextCursor: String? = null,
    ): ImagePage = withContext(Dispatchers.IO) {
        val url = buildUrl(settings, nextUrl, nextCursor)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "PromptDatasetBuilder/${BuildConfig.VERSION_NAME} Android")
            .apply {
                settings.apiKey.trim().takeIf(String::isNotBlank)?.let { key ->
                    header("Authorization", "Bearer $key")
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            val contentType = response.header("Content-Type").orEmpty()
            val diagnosticBase = NetworkDiagnostic(
                endpoint = response.request.url.newBuilder().query(null).build().toString(),
                httpStatus = response.code,
                contentType = contentType.ifBlank { "не указан" },
                receivedBytes = body.toByteArray(Charsets.UTF_8).size,
            )

            if (contentType.contains("text/html", ignoreCase = true) ||
                CivitaiResponseParser.looksLikeHtml(body)
            ) {
                throw CivitaiApiException(
                    "Civitai вернул HTML вместо API-данных. " +
                        "Это обычно означает блокировку со стороны сети, DNS, VPN или Cloudflare. " +
                        "HTTP ${response.code}; Content-Type: ${contentType.ifBlank { "не указан" }}.",
                    diagnosticBase.copy(message = "Получен HTML вместо JSON"),
                )
            }

            if (!response.isSuccessful) {
                val details = extractError(body)
                val explanation = when (response.code) {
                    401 -> "API-ключ отклонён. Создайте новый ключ Civitai и вставьте его без кавычек."
                    403 -> "Доступ запрещён. Проверьте API-ключ, возраст 18+ и сетевые ограничения."
                    429 -> "Превышен лимит запросов. Подождите несколько минут и обновите ленту."
                    else -> "Civitai API вернул ошибку HTTP ${response.code}."
                }
                throw CivitaiApiException(
                    "$explanation ${details.take(260)}".trim(),
                    diagnosticBase.copy(message = "Ошибка HTTP ${response.code}"),
                )
            }

            val parsed = runCatching { CivitaiResponseParser.parse(body) }.getOrElse { error ->
                throw CivitaiApiException(
                    error.message ?: "Не удалось разобрать ответ Civitai",
                    diagnosticBase.copy(message = "Ошибка разбора JSON"),
                    error,
                )
            }

            ImagePage(
                items = parsed.items,
                nextUrl = validateNextPage(parsed.nextPage),
                nextCursor = parsed.nextCursor,
                diagnostic = diagnosticBase.copy(
                    parsedItems = parsed.rawItemCount,
                    itemsWithPrompt = parsed.items.size,
                    message = "Соединение работает",
                ),
            )
        }
    }

    suspend fun testConnection(settings: AppSettings): ImagePage =
        loadPage(settings.copy(pageSize = 10))

    private fun buildUrl(
        settings: AppSettings,
        nextUrl: String?,
        nextCursor: String?,
    ): HttpUrl {
        val validatedNext = validateNextPage(nextUrl)?.toHttpUrlOrNull()
        if (validatedNext != null) return validatedNext

        return AppSettings.API_ENDPOINT.toHttpUrl().newBuilder()
            .setQueryParameter("limit", settings.pageSize.coerceIn(10, 100).toString())
            .setQueryParameter("sort", "Newest")
            .setQueryParameter("period", "AllTime")
            .setQueryParameter("nsfw", settings.includeNsfw.toString())
            .apply {
                nextCursor?.trim()?.takeIf(String::isNotBlank)?.let { cursor ->
                    setQueryParameter("cursor", cursor)
                }
            }
            .build()
    }

    private fun validateNextPage(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val url = value.toHttpUrlOrNull() ?: return null
        val host = url.host.removePrefix("www.")
        return if (url.isHttps && host == "civitai.com") url.toString() else null
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val root = org.json.JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: root.optString("message").takeIf(String::isNotBlank)
                ?: ""
        }.getOrElse {
            body.trim().replace(Regex("\\s+"), " ").take(260)
        }
    }
}

class CivitaiApiException(
    message: String,
    val diagnostic: NetworkDiagnostic,
    cause: Throwable? = null,
) : IOException(message, cause)

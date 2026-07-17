package com.promptdatasetbuilder.app.network

import com.promptdatasetbuilder.app.BuildConfig
import com.promptdatasetbuilder.app.data.AppSettings
import com.promptdatasetbuilder.app.data.ImagePage
import com.promptdatasetbuilder.app.data.NetworkDiagnostic
import com.promptdatasetbuilder.app.data.SourceImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CivitaiApiClient {
    private val client = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun loadPage(
        settings: AppSettings,
        preferredSource: String? = null,
        nextCursor: String? = null,
    ): ImagePage = withContext(Dispatchers.IO) {
        val sources = orderedSources(preferredSource)
        var lastError: CivitaiApiException? = null

        for ((index, source) in sources.withIndex()) {
            try {
                val page = loadFromSource(source, settings, nextCursor)
                val feedCount = page.diagnostic.parsedItems ?: 0
                if (feedCount == 0 && index < sources.lastIndex) continue
                return@withContext page
            } catch (error: CivitaiApiException) {
                lastError = error
            }
        }

        throw lastError ?: CivitaiApiException(
            "Не удалось получить ленту ни с civita.red, ни с civitai.com.",
            NetworkDiagnostic(message = "Оба источника недоступны"),
        )
    }

    suspend fun testConnection(settings: AppSettings): ImagePage =
        loadPage(settings.copy(pageSize = 6), preferredSource = null, nextCursor = null)

    private suspend fun loadFromSource(
        source: String,
        settings: AppSettings,
        nextCursor: String?,
    ): ImagePage {
        val feedEndpoint = "image.getInfinite"
        val feedParams = JSONObject()
            .put("useIndex", true)
            .put("period", "AllTime")
            .put("sort", "Newest")
            .put("withMeta", false)
            .put("fromPlatform", false)
            .put("browsingLevel", if (settings.includeNsfw) 31 else 1)
            .put("include", JSONArray().put("cosmetics"))
            .put("types", JSONArray().put("image"))
            .put("limit", settings.pageSize.coerceIn(6, 30))
            .put("cursor", cursorJsonValue(nextCursor))

        val feedResult = try {
            executeTrpc(
                source = source,
                endpoint = feedEndpoint,
                json = feedParams,
                apiKey = settings.apiKey,
                markUndefinedCursor = nextCursor == null,
            )
        } catch (error: Throwable) {
            val diagnostic = (error as? TrpcHttpException)?.diagnostic
                ?: NetworkDiagnostic(
                    endpoint = "$source${AppSettings.TRPC_PATH}$feedEndpoint",
                    sourceHost = source,
                    message = "Ошибка запроса ленты",
                )
            throw CivitaiApiException(
                "Источник $source не ответил корректно: ${error.message.orEmpty()}",
                diagnostic,
                error,
            )
        }

        val parsedFeed = try {
            CivitaiResponseParser.parseFeed(feedResult.body)
        } catch (error: Throwable) {
            throw CivitaiApiException(
                "Не удалось разобрать ленту $source: ${error.message.orEmpty()}",
                feedResult.diagnostic.copy(message = "Ошибка разбора ленты"),
                error,
            )
        }

        val semaphore = Semaphore(4)
        val promptResults = coroutineScope {
            parsedFeed.items.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        fetchPromptWithFallback(
                            preferredSource = source,
                            imageId = candidate.id,
                            apiKey = settings.apiKey,
                        )
                    }
                }
            }.awaitAll()
        }

        val images = parsedFeed.items.zip(promptResults).mapNotNull { (candidate, promptResult) ->
            val prompt = promptResult.prompt?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SourceImage(
                id = candidate.id,
                url = candidate.url,
                width = candidate.width,
                height = candidate.height,
                nsfwLevel = candidate.nsfwLevel,
                prompt = prompt,
                username = candidate.username,
            )
        }.distinctBy(SourceImage::id)

        return ImagePage(
            items = images,
            nextCursor = parsedFeed.nextCursor,
            sourceBaseUrl = source,
            diagnostic = feedResult.diagnostic.copy(
                parsedItems = parsedFeed.items.size,
                generationChecked = promptResults.size,
                generationSucceeded = promptResults.count(PromptResult::requestSucceeded),
                itemsWithPrompt = images.size,
                message = when {
                    images.isNotEmpty() -> "Лента и промпты загружены"
                    parsedFeed.items.isEmpty() -> "Источник вернул пустую страницу"
                    else -> "Кандидаты найдены, но открытых промптов на странице нет"
                },
            ),
        )
    }

    private suspend fun fetchPromptWithFallback(
        preferredSource: String,
        imageId: String,
        apiKey: String,
    ): PromptResult {
        val numericId = imageId.toLongOrNull() ?: return PromptResult(null, false)
        for (source in orderedSources(preferredSource)) {
            val result = runCatching {
                executeTrpc(
                    source = source,
                    endpoint = "image.getGenerationData",
                    json = JSONObject().put("id", numericId),
                    apiKey = apiKey,
                    markUndefinedCursor = false,
                    maxAttempts = 2,
                )
            }.getOrNull() ?: continue

            val prompt = runCatching {
                CivitaiResponseParser.parseGenerationPrompt(result.body)
            }.getOrNull()
            return PromptResult(prompt, true)
        }
        return PromptResult(null, false)
    }

    private suspend fun executeTrpc(
        source: String,
        endpoint: String,
        json: JSONObject,
        apiKey: String,
        markUndefinedCursor: Boolean,
        maxAttempts: Int = 3,
    ): TrpcResult {
        val input = JSONObject().put("json", json)
        if (markUndefinedCursor) {
            input.put(
                "meta",
                JSONObject().put(
                    "values",
                    JSONObject().put("cursor", JSONArray().put("undefined")),
                ),
            )
        }

        val url = "$source${AppSettings.TRPC_PATH}$endpoint"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("input", input.toString())
            .build()

        var lastException: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PromptDatasetBuilder/${BuildConfig.VERSION_NAME} Android")
                    .header("x-client-version", "5.0.1386")
                    .header("x-client-date", System.currentTimeMillis().toString())
                    .header("x-client", "web")
                    .header("x-fingerprint", "undefined")
                    .apply {
                        apiKey.trim().takeIf(String::isNotBlank)?.let { key ->
                            header("Authorization", "Bearer $key")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    val contentType = response.header("Content-Type").orEmpty()
                    val diagnostic = NetworkDiagnostic(
                        endpoint = "$source${AppSettings.TRPC_PATH}$endpoint",
                        sourceHost = source,
                        httpStatus = response.code,
                        contentType = contentType.ifBlank { "не указан" },
                        receivedBytes = body.toByteArray(Charsets.UTF_8).size,
                        message = "HTTP ${response.code}",
                    )

                    if (contentType.contains("text/html", ignoreCase = true) ||
                        CivitaiResponseParser.looksLikeHtml(body)
                    ) {
                        throw TrpcHttpException(
                            "$source вернул HTML вместо tRPC JSON",
                            diagnostic.copy(message = "Получен HTML вместо JSON"),
                        )
                    }

                    if (response.isSuccessful) {
                        return TrpcResult(body, diagnostic)
                    }

                    val retryable = response.code == 429 || response.code in 500..599
                    val message = when (response.code) {
                        401 -> "API-ключ отклонён"
                        403 -> "Доступ запрещён; проверьте API-ключ и возраст 18+"
                        429 -> "Слишком много запросов"
                        else -> "HTTP ${response.code}: ${extractError(body)}"
                    }
                    val exception = TrpcHttpException(message, diagnostic)
                    if (!retryable || attempt == maxAttempts - 1) throw exception
                    lastException = exception
                }
            } catch (error: Throwable) {
                lastException = error
                if (attempt == maxAttempts - 1) throw error
            }
            delay(450L * (attempt + 1))
        }

        throw lastException ?: IOException("Неизвестная ошибка tRPC")
    }

    private fun orderedSources(preferredSource: String?): List<String> {
        val primary = preferredSource?.takeIf {
            it == AppSettings.PRIMARY_SOURCE || it == AppSettings.FALLBACK_SOURCE
        } ?: AppSettings.PRIMARY_SOURCE
        val secondary = if (primary == AppSettings.PRIMARY_SOURCE) {
            AppSettings.FALLBACK_SOURCE
        } else {
            AppSettings.PRIMARY_SOURCE
        }
        return listOf(primary, secondary)
    }

    private fun cursorJsonValue(cursor: String?): Any {
        val normalized = cursor?.trim().orEmpty()
        if (normalized.isEmpty()) return JSONObject.NULL
        return normalized.toLongOrNull() ?: normalized
    }

    private fun extractError(body: String): String {
        if (body.isBlank()) return "пустой ответ"
        return runCatching {
            val root = JSONObject(body)
            root.optJSONObject("error")
                ?.optJSONObject("json")
                ?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: root.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf(String::isNotBlank)
                ?: body.trim().replace(Regex("\\s+"), " ").take(260)
        }.getOrElse {
            body.trim().replace(Regex("\\s+"), " ").take(260)
        }
    }

    private data class PromptResult(
        val prompt: String?,
        val requestSucceeded: Boolean,
    )

    private data class TrpcResult(
        val body: String,
        val diagnostic: NetworkDiagnostic,
    )
}

class CivitaiApiException(
    message: String,
    val diagnostic: NetworkDiagnostic,
    cause: Throwable? = null,
) : IOException(message, cause)

private class TrpcHttpException(
    message: String,
    val diagnostic: NetworkDiagnostic,
    cause: Throwable? = null,
) : IOException(message, cause)

package com.civitared.promptdataset.network

import com.civitared.promptdataset.data.AppSettings
import com.civitared.promptdataset.data.ImagePage
import com.civitared.promptdataset.data.SourceImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class CivitaApiClient {
    private val client = OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun loadPage(
        settings: AppSettings,
        nextUrl: String? = null,
        nextCursor: String? = null,
    ): ImagePage = withContext(Dispatchers.IO) {
        val url = buildUrl(settings, nextUrl, nextCursor)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "PromptDatasetBuilder/0.1 Android")
            .apply {
                if (settings.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${settings.apiKey.trim()}")
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(
                    "API вернул ${response.code}: ${body.take(300).ifBlank { response.message }}",
                )
            }
            runCatching { parsePage(body) }.getOrElse { error ->
                throw IOException("API вернул неподдерживаемый JSON: ${error.message}", error)
            }
        }
    }

    suspend fun testConnection(settings: AppSettings): Int {
        val page = loadPage(settings.copy(pageSize = 3))
        return page.items.size
    }

    private fun buildUrl(
        settings: AppSettings,
        nextUrl: String?,
        nextCursor: String?,
    ): String {
        val endpoint = settings.endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Некорректный HTTPS endpoint")
        require(endpoint.isHttps) { "Разрешены только HTTPS endpoint" }

        if (!nextUrl.isNullOrBlank()) {
            val next = nextUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("API вернул некорректный адрес следующей страницы")
            require(next.isHttps && next.host == endpoint.host && next.port == endpoint.port) {
                "API вернул небезопасный адрес следующей страницы"
            }
            return next.toString()
        }

        return endpoint.newBuilder()
            .setQueryParameter("limit", settings.pageSize.toString())
            .setQueryParameter("sort", "Newest")
            .setQueryParameter("period", "AllTime")
            .setQueryParameter("nsfw", if (settings.includeNsfw) "X" else "None")
            .apply {
                if (!nextCursor.isNullOrBlank()) {
                    setQueryParameter("cursor", nextCursor)
                }
            }
            .build()
            .toString()
    }

    private fun parsePage(raw: String): ImagePage {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Пустой ответ" }

        val root: JSONObject?
        val itemsJson: JSONArray
        if (trimmed.startsWith("[")) {
            root = null
            itemsJson = JSONArray(trimmed)
        } else {
            root = JSONObject(trimmed)
            itemsJson = root.optJSONArray("items")
                ?: root.optJSONArray("data")
                ?: throw IllegalArgumentException("Не найден массив items")
        }

        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                val url = firstHttpsUrl(
                    item.optString("url"),
                    item.optString("imageUrl"),
                    item.optString("src"),
                ) ?: continue
                val id = item.opt("id")
                    ?.toString()
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: sha256(url)

                val meta = item.optJSONObject("meta")
                    ?: item.optString("meta")
                        .takeIf { it.trim().startsWith("{") }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }

                val prompt = sequenceOf(
                    meta?.optString("prompt"),
                    item.optString("prompt"),
                )
                    .filterNotNull()
                    .map(String::trim)
                    .firstOrNull { it.isNotBlank() && it != "null" }
                    ?: continue

                val userObject = item.optJSONObject("user")
                add(
                    SourceImage(
                        id = id,
                        url = url,
                        width = item.optInt("width", 1).coerceAtLeast(1),
                        height = item.optInt("height", 1).coerceAtLeast(1),
                        nsfwLevel = item.optString(
                            "nsfwLevel",
                            if (item.optBoolean("nsfw", false)) "X" else "None",
                        ).ifBlank { "Unknown" },
                        prompt = prompt,
                        username = sequenceOf(
                            item.optString("username"),
                            userObject?.optString("username"),
                            userObject?.optString("name"),
                        )
                            .filterNotNull()
                            .map(String::trim)
                            .firstOrNull { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }

        val metadata = root?.optJSONObject("metadata")
        return ImagePage(
            items = items.distinctBy(SourceImage::id),
            nextUrl = metadata?.optString("nextPage")
                ?.takeIf { it.startsWith("https://") },
            nextCursor = metadata?.opt("nextCursor")
                ?.toString()
                ?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun firstHttpsUrl(vararg candidates: String): String? =
        candidates.firstOrNull { it.startsWith("https://") }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

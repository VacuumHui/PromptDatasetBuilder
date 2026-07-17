package com.promptdatasetbuilder.app.network

import org.json.JSONArray
import org.json.JSONObject

object CivitaiResponseParser {
    data class FeedImage(
        val id: String,
        val url: String,
        val width: Int,
        val height: Int,
        val nsfwLevel: String,
        val username: String?,
    )

    data class ParsedFeed(
        val items: List<FeedImage>,
        val nextCursor: String?,
    )

    fun parseFeed(raw: String): ParsedFeed {
        val payload = unwrapTrpc(raw)
        val rawItems = payload.optJSONArray("items")
            ?: throw IllegalArgumentException("В tRPC-ответе отсутствует массив items")

        val items = ArrayList<FeedImage>(rawItems.length())
        for (index in 0 until rawItems.length()) {
            val item = rawItems.optJSONObject(index) ?: continue
            parseFeedItem(item)?.let(items::add)
        }

        return ParsedFeed(
            items = items.distinctBy(FeedImage::id),
            nextCursor = scalarString(payload.opt("nextCursor")),
        )
    }

    fun parseGenerationPrompt(raw: String): String? {
        val payload = unwrapTrpc(raw)
        return findPrompt(payload)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    fun looksLikeHtml(value: String): Boolean {
        val start = value.trimStart().take(128)
        return start.startsWith("<!DOCTYPE", ignoreCase = true) ||
            start.startsWith("<html", ignoreCase = true) ||
            start.startsWith("<head", ignoreCase = true) ||
            start.startsWith("<body", ignoreCase = true)
    }

    private fun unwrapTrpc(raw: String): JSONObject {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        require(trimmed.isNotBlank()) { "Сервер вернул пустой ответ" }
        require(!looksLikeHtml(trimmed)) { "Сервер вернул HTML вместо JSON" }

        val root = runCatching { JSONObject(trimmed) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Сервер вернул некорректный JSON: ${safeMessage(error)}",
                error,
            )
        }

        root.optJSONObject("error")?.let { errorObject ->
            val message = errorObject.optJSONObject("json")
                ?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: errorObject.optString("message").takeIf(String::isNotBlank)
                ?: errorObject.toString().take(350)
            throw IllegalArgumentException("Ошибка tRPC: $message")
        }

        return root.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONObject("json")
            ?: throw IllegalArgumentException(
                "Неожиданная структура tRPC: отсутствует result.data.json",
            )
    }

    private fun parseFeedItem(item: JSONObject): FeedImage? {
        val id = scalarString(item.opt("id")) ?: return null
        val url = sequenceOf(
            item.optString("url"),
            item.optString("imageUrl"),
            item.optString("src"),
        ).firstOrNull { it.startsWith("https://") } ?: return null

        val nsfwRaw = item.opt("nsfwLevel")
        val nsfwLevel = when (nsfwRaw) {
            is Number -> when (nsfwRaw.toInt()) {
                1 -> "Safe"
                2 -> "Soft"
                4 -> "Mature"
                8 -> "X"
                16 -> "Blocked"
                else -> nsfwRaw.toString()
            }
            is String -> nsfwRaw.ifBlank { "Safe" }
            else -> if (item.optBoolean("nsfw", false)) "NSFW" else "Safe"
        }

        val username = item.optJSONObject("user")
            ?.optString("username")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: item.optString("username")
                .trim()
                .takeIf(String::isNotBlank)

        return FeedImage(
            id = id,
            url = url,
            width = item.optInt("width", 1).coerceAtLeast(1),
            height = item.optInt("height", 1).coerceAtLeast(1),
            nsfwLevel = nsfwLevel,
            username = username,
        )
    }

    private fun findPrompt(value: Any?, depth: Int = 0): String? {
        if (depth > 8) return null
        return when (value) {
            is JSONObject -> {
                val keys = listOf("prompt", "Prompt", "positivePrompt", "positive_prompt")
                for (key in keys) {
                    val candidate = value.opt(key)
                    if (candidate is String && candidate.isNotBlank() && candidate != "null") {
                        return candidate
                    }
                }

                val meta = value.opt("meta")
                when (meta) {
                    is JSONObject -> findPrompt(meta, depth + 1)?.let { return it }
                    is String -> parseEmbeddedJson(meta)?.let { findPrompt(it, depth + 1) }
                        ?.let { return it }
                }

                val iterator = value.keys()
                while (iterator.hasNext()) {
                    findPrompt(value.opt(iterator.next()), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findPrompt(value.opt(index), depth + 1)?.let { return it }
                }
                null
            }
            is String -> parseEmbeddedJson(value)?.let { findPrompt(it, depth + 1) }
            else -> null
        }
    }

    private fun parseEmbeddedJson(text: String): JSONObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun scalarString(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotBlank() && it != "null" }
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun safeMessage(error: Throwable): String = error.message
        .orEmpty()
        .replace(Regex("\\s+"), " ")
        .take(220)
        .ifBlank { "не удалось разобрать ответ" }
}

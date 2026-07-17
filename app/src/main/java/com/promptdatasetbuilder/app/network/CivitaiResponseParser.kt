package com.promptdatasetbuilder.app.network

import com.promptdatasetbuilder.app.data.SourceImage
import org.json.JSONArray
import org.json.JSONObject

object CivitaiResponseParser {
    data class ParsedPage(
        val items: List<SourceImage>,
        val rawItemCount: Int,
        val nextPage: String?,
        val nextCursor: String?,
    )

    fun parse(raw: String): ParsedPage {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        require(trimmed.isNotBlank()) { "Civitai вернул пустой ответ" }
        require(!looksLikeHtml(trimmed)) {
            "Civitai вернул HTML-страницу вместо JSON"
        }

        val root = runCatching { JSONObject(trimmed) }.getOrElse { error ->
            throw IllegalArgumentException(
                "Civitai вернул некорректный JSON: ${safeMessage(error)}",
                error,
            )
        }

        root.optJSONObject("error")?.let { errorObject ->
            val message = errorObject.optString("message")
                .ifBlank { errorObject.optString("name") }
                .ifBlank { errorObject.toString().take(300) }
            throw IllegalArgumentException("Ошибка Civitai API: $message")
        }

        val rawItems = root.optJSONArray("items")
            ?: throw IllegalArgumentException("В ответе Civitai отсутствует массив items")

        val parsed = ArrayList<SourceImage>(rawItems.length())
        for (index in 0 until rawItems.length()) {
            val item = rawItems.optJSONObject(index) ?: continue
            val parsedItem = parseItem(item)
            if (parsedItem != null) parsed.add(parsedItem)
        }

        val metadata = root.optJSONObject("metadata")
        return ParsedPage(
            items = parsed.distinctBy(SourceImage::id),
            rawItemCount = rawItems.length(),
            nextPage = metadata?.optString("nextPage")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" },
            nextCursor = scalarString(metadata?.opt("nextCursor")),
        )
    }

    fun looksLikeHtml(value: String): Boolean {
        val start = value.trimStart().take(96)
        return start.startsWith("<!DOCTYPE", ignoreCase = true) ||
            start.startsWith("<html", ignoreCase = true) ||
            start.startsWith("<head", ignoreCase = true) ||
            start.startsWith("<body", ignoreCase = true)
    }

    private fun parseItem(item: JSONObject): SourceImage? {
        val id = scalarString(item.opt("id")) ?: return null
        val url = listOf(
            item.optString("url"),
            item.optString("imageUrl"),
            item.optString("src"),
        ).firstOrNull { it.startsWith("https://") } ?: return null

        val prompt = extractPrompt(item)?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        return SourceImage(
            id = id,
            url = url,
            width = item.optInt("width", 1).coerceAtLeast(1),
            height = item.optInt("height", 1).coerceAtLeast(1),
            nsfwLevel = item.optString("nsfwLevel")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: if (item.optBoolean("nsfw", false)) "NSFW" else "Safe",
            prompt = prompt,
            username = item.optString("username")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: item.optJSONObject("user")
                    ?.optString("username")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun extractPrompt(item: JSONObject): String? {
        val meta = item.opt("meta")
        return when (meta) {
            is JSONObject -> directPrompt(meta) ?: findPromptRecursive(meta)
            is String -> {
                val text = meta.trim()
                if (text.startsWith("{")) {
                    runCatching { JSONObject(text) }
                        .getOrNull()
                        ?.let { directPrompt(it) ?: findPromptRecursive(it) }
                } else {
                    null
                }
            }
            else -> null
        } ?: directPrompt(item)
    }

    private fun directPrompt(value: JSONObject): String? {
        val candidates = listOf("prompt", "Prompt", "positivePrompt", "positive_prompt")
        for (key in candidates) {
            val candidate = value.opt(key)
            if (candidate is String && candidate.isNotBlank() && candidate != "null") {
                return candidate
            }
        }
        return null
    }

    private fun findPromptRecursive(value: Any?, depth: Int = 0): String? {
        if (depth > 6) return null
        return when (value) {
            is JSONObject -> {
                directPrompt(value)?.let { return it }
                val keys = value.keys()
                while (keys.hasNext()) {
                    findPromptRecursive(value.opt(keys.next()), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findPromptRecursive(value.opt(index), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
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
        .take(180)
        .ifBlank { "не удалось разобрать ответ" }
}

package com.civitared.promptdataset.data

data class SourceImage(
    val id: String,
    val url: String,
    val width: Int,
    val height: Int,
    val nsfwLevel: String,
    val prompt: String,
    val username: String?,
)

data class ImagePage(
    val items: List<SourceImage>,
    val nextUrl: String?,
    val nextCursor: String?,
)

data class DatasetEntry(
    val id: Long,
    val sourceImageId: String,
    val command: String,
    val input: String,
    val output: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AppSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val includeNsfw: Boolean = false,
    val command: String = DEFAULT_COMMAND,
    val pageSize: Int = 30,
    val ageConfirmed: Boolean = false,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://civita.red/api/v1/images"
        const val OFFICIAL_ENDPOINT = "https://civitai.com/api/v1/images"
        const val DEFAULT_COMMAND =
            "Составь подробный англоязычный промпт для генерации изображения на основе описания пользователя."
    }
}

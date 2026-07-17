package com.promptdatasetbuilder.app.data

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
    val diagnostic: NetworkDiagnostic,
)

data class NetworkDiagnostic(
    val endpoint: String = AppSettings.API_ENDPOINT,
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val receivedBytes: Int? = null,
    val parsedItems: Int? = null,
    val itemsWithPrompt: Int? = null,
    val message: String = "Запрос ещё не выполнялся",
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
    val apiKey: String = "",
    val includeNsfw: Boolean = false,
    val command: String = DEFAULT_COMMAND,
    val pageSize: Int = 30,
    val ageConfirmed: Boolean = false,
) {
    companion object {
        const val API_ENDPOINT = "https://civitai.com/api/v1/images"
        const val DEFAULT_COMMAND =
            "Составь подробный англоязычный промпт для генерации изображения на основе описания пользователя."
    }
}

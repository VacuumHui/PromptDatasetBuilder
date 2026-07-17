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
    val nextCursor: String?,
    val sourceBaseUrl: String,
    val diagnostic: NetworkDiagnostic,
)

data class NetworkDiagnostic(
    val endpoint: String = AppSettings.PRIMARY_SOURCE + AppSettings.TRPC_PATH + "image.getInfinite",
    val sourceHost: String = "ещё не выбран",
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val receivedBytes: Int? = null,
    val parsedItems: Int? = null,
    val generationChecked: Int? = null,
    val generationSucceeded: Int? = null,
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
    val pageSize: Int = 12,
    val ageConfirmed: Boolean = false,
) {
    companion object {
        const val PRIMARY_SOURCE = "https://civita.red"
        const val FALLBACK_SOURCE = "https://civitai.com"
        const val TRPC_PATH = "/api/trpc/"
        const val DEFAULT_COMMAND =
            "Составь подробный англоязычный промпт для генерации изображения на основе описания пользователя."
    }
}

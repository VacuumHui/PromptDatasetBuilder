package com.civitared.promptdataset.data

object SettingsRules {
    fun normalizeEndpoint(value: String): String {
        var endpoint = value.trim()
        if (endpoint.isBlank()) return AppSettings.DEFAULT_ENDPOINT

        endpoint = when {
            endpoint.startsWith("https://", ignoreCase = true) -> endpoint
            endpoint.startsWith("http://", ignoreCase = true) ->
                "https://${endpoint.substringAfter("://")}" 
            else -> "https://$endpoint"
        }

        endpoint = endpoint
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')

        if (!endpoint.endsWith("/api/v1/images")) {
            endpoint += "/api/v1/images"
        }
        return endpoint
    }

    fun validationError(settings: AppSettings): String? = when {
        settings.includeNsfw && !settings.ageConfirmed ->
            "Для NSFW-ленты требуется подтверждение возраста 18+"

        settings.includeNsfw && settings.apiKey.isBlank() ->
            "Для NSFW-ленты укажите пользовательский API-ключ"

        settings.command.isBlank() ->
            "Команда датасета не должна быть пустой"

        else -> null
    }
}

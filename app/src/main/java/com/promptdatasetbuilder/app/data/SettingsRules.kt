package com.promptdatasetbuilder.app.data

object SettingsRules {
    fun validationError(settings: AppSettings): String? = when {
        settings.command.isBlank() -> "Команда не должна быть пустой"
        settings.pageSize !in 10..100 -> "Размер страницы должен быть от 10 до 100"
        settings.includeNsfw && !settings.ageConfirmed ->
            "Для NSFW необходимо подтвердить возраст 18+"
        settings.includeNsfw && settings.apiKey.isBlank() ->
            "Для NSFW необходимо указать API-ключ Civitai"
        else -> null
    }
}

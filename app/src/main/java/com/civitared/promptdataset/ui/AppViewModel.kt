package com.civitared.promptdataset.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.civitared.promptdataset.data.AppDatabase
import com.civitared.promptdataset.data.AppSettings
import com.civitared.promptdataset.data.DatasetEntry
import com.civitared.promptdataset.data.SecureSettingsStore
import com.civitared.promptdataset.data.SettingsRules
import com.civitared.promptdataset.data.SourceImage
import com.civitared.promptdataset.network.CivitaApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val settings: AppSettings = AppSettings(),
    val images: List<SourceImage> = emptyList(),
    val selectedImage: SourceImage? = null,
    val entries: List<DatasetEntry> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val message: String? = null,
    val error: String? = null,
    val connectionTesting: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase(application)
    private val settingsStore = SecureSettingsStore(application)
    private val api = CivitaApiClient()

    private val _uiState = MutableStateFlow(
        AppUiState(settings = settingsStore.load()),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var nextUrl: String? = null
    private var nextCursor: String? = null
    private var loadJob: Job? = null
    private val sessionSkipped = LinkedHashSet<String>()

    init {
        refreshEntries()
    }

    fun refreshGallery() {
        loadJob?.cancel()
        nextUrl = null
        nextCursor = null
        _uiState.value = _uiState.value.copy(
            images = emptyList(),
            canLoadMore = true,
            error = null,
        )
        loadPage(reset = true)
    }

    fun loadMore() {
        if (!_uiState.value.canLoadMore || _uiState.value.loadingMore) return
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = reset,
                loadingMore = !reset,
                error = null,
            )
            runCatching {
                val settings = _uiState.value.settings
                val page = api.loadPage(settings, nextUrl, nextCursor)
                val processed = withContext(Dispatchers.IO) { database.getProcessedIds() }
                val visible = page.items.filterNot {
                    it.id in processed || it.id in sessionSkipped
                }
                nextUrl = page.nextUrl
                nextCursor = page.nextCursor

                val merged = if (reset) {
                    visible
                } else {
                    (_uiState.value.images + visible).distinctBy { it.id }
                }
                val hasNext = page.nextUrl != null || page.nextCursor != null
                _uiState.value = _uiState.value.copy(
                    images = merged,
                    loading = false,
                    loadingMore = false,
                    canLoadMore = hasNext,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = error.message ?: "Ошибка загрузки",
                )
            }
        }
    }

    fun selectImage(image: SourceImage) {
        _uiState.value = _uiState.value.copy(selectedImage = image)
    }

    fun closeAnnotation() {
        _uiState.value = _uiState.value.copy(selectedImage = null)
    }

    fun skipForSession(sourceImageId: String) {
        sessionSkipped += sourceImageId
        _uiState.value = _uiState.value.copy(
            selectedImage = null,
            images = _uiState.value.images.filterNot { it.id == sourceImageId },
        )
    }

    fun ignorePermanently(sourceImageId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.markIgnored(sourceImageId) }
            _uiState.value = _uiState.value.copy(
                selectedImage = null,
                images = _uiState.value.images.filterNot { it.id == sourceImageId },
                message = "Изображение больше не будет показываться",
            )
        }
    }

    fun saveEntry(
        image: SourceImage,
        input: String,
        output: String,
    ) {
        val command = _uiState.value.settings.command.trim()
        if (command.isBlank() || input.isBlank() || output.isBlank()) {
            _uiState.value = _uiState.value.copy(
                error = "Команда, ввод и вывод не должны быть пустыми",
            )
            return
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    database.saveEntry(
                        sourceImageId = image.id,
                        command = command,
                        input = input,
                        output = output,
                    )
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    selectedImage = null,
                    images = _uiState.value.images.filterNot { it.id == image.id },
                    message = "Запись сохранена",
                )
                refreshEntries()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    error = it.message ?: "Не удалось сохранить запись",
                )
            }
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { database.getEntries() }
            _uiState.value = _uiState.value.copy(entries = entries)
        }
    }

    fun deleteEntry(entryId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { database.deleteEntry(entryId) }
            refreshEntries()
            _uiState.value = _uiState.value.copy(message = "Запись удалена")
        }
    }

    fun saveSettings(settings: AppSettings) {
        SettingsRules.validationError(settings)?.let { validationError ->
            _uiState.value = _uiState.value.copy(error = validationError)
            return
        }

        val normalized = settings.copy(
            endpoint = settingsStore.normalizeEndpoint(settings.endpoint),
            apiKey = settings.apiKey.trim(),
            command = settings.command.trim().ifBlank { AppSettings.DEFAULT_COMMAND },
            pageSize = settings.pageSize.coerceIn(10, 100),
        )
        settingsStore.save(normalized)
        _uiState.value = _uiState.value.copy(
            settings = normalized,
            message = "Настройки сохранены",
        )
        refreshGallery()
    }

    fun testConnection(settings: AppSettings) {
        SettingsRules.validationError(settings)?.let { validationError ->
            _uiState.value = _uiState.value.copy(error = validationError)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectionTesting = true, error = null)
            runCatching {
                val normalized = settings.copy(
                    endpoint = settingsStore.normalizeEndpoint(settings.endpoint),
                    apiKey = settings.apiKey.trim(),
                )
                api.testConnection(normalized)
            }.onSuccess { count ->
                _uiState.value = _uiState.value.copy(
                    connectionTesting = false,
                    message = "Соединение работает. Получено записей с промптами: $count",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    connectionTesting = false,
                    error = it.message ?: "Проверка соединения не удалась",
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}

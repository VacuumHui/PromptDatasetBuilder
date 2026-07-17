package com.promptdatasetbuilder.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.promptdatasetbuilder.app.data.AppDatabase
import com.promptdatasetbuilder.app.data.AppSettings
import com.promptdatasetbuilder.app.data.DatasetEntry
import com.promptdatasetbuilder.app.data.NetworkDiagnostic
import com.promptdatasetbuilder.app.data.SecureSettingsStore
import com.promptdatasetbuilder.app.data.SettingsRules
import com.promptdatasetbuilder.app.data.SourceImage
import com.promptdatasetbuilder.app.network.CivitaiApiClient
import com.promptdatasetbuilder.app.network.CivitaiApiException
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
    val entries: List<DatasetEntry> = emptyList(),
    val selectedImage: SourceImage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val connectionTesting: Boolean = false,
    val nextUrl: String? = null,
    val nextCursor: String? = null,
    val canLoadMore: Boolean = false,
    val diagnostic: NetworkDiagnostic = NetworkDiagnostic(),
    val message: String? = null,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase(application)
    private val settingsStore = SecureSettingsStore(application)
    private val api = CivitaiApiClient()
    private val sessionSkipped = LinkedHashSet<String>()
    private var galleryJob: Job? = null

    private val _uiState = MutableStateFlow(
        AppUiState(settings = settingsStore.load()),
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        refreshEntries()
    }

    fun refreshGallery() {
        galleryJob?.cancel()
        galleryJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                loadingMore = false,
                images = emptyList(),
                nextUrl = null,
                nextCursor = null,
                canLoadMore = false,
                error = null,
            )
            loadPagesUntilUseful(reset = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.canLoadMore) return

        galleryJob?.cancel()
        galleryJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingMore = true, error = null)
            loadPagesUntilUseful(reset = false)
        }
    }

    private suspend fun loadPagesUntilUseful(reset: Boolean) {
        var nextUrl: String? = if (reset) null else _uiState.value.nextUrl
        var nextCursor: String? = if (reset) null else _uiState.value.nextCursor
        val collected = ArrayList<SourceImage>()
        var lastDiagnostic = _uiState.value.diagnostic
        var attempts = 0

        try {
            val processed = withContext(Dispatchers.IO) { database.getProcessedIds() }
            while (attempts < 4) {
                attempts++
                val page = api.loadPage(
                    settings = _uiState.value.settings,
                    nextUrl = nextUrl,
                    nextCursor = nextCursor,
                )
                lastDiagnostic = page.diagnostic
                nextUrl = page.nextUrl
                nextCursor = page.nextCursor

                collected += page.items.filterNot { image ->
                    image.id in processed || image.id in sessionSkipped
                }

                if (collected.isNotEmpty() || (nextUrl == null && nextCursor == null)) break
            }

            val existing: List<SourceImage> = if (reset) emptyList() else _uiState.value.images
            val merged = (existing + collected).distinctBy(SourceImage::id)
            _uiState.value = _uiState.value.copy(
                images = merged,
                loading = false,
                loadingMore = false,
                nextUrl = nextUrl,
                nextCursor = nextCursor,
                canLoadMore = nextUrl != null || nextCursor != null,
                diagnostic = lastDiagnostic,
                message = if (collected.isEmpty() && (nextUrl != null || nextCursor != null)) {
                    "На просмотренных страницах нет новых изображений с промптами"
                } else {
                    null
                },
            )
        } catch (error: CivitaiApiException) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                diagnostic = error.diagnostic,
                error = error.message ?: "Ошибка Civitai API",
            )
        } catch (error: Throwable) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                error = error.message ?: "Ошибка загрузки",
            )
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

    fun saveEntry(image: SourceImage, input: String, output: String) {
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
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: "Не удалось сохранить запись",
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
                api.testConnection(
                    settings.copy(
                        apiKey = settings.apiKey.trim(),
                        command = settings.command.trim().ifBlank { AppSettings.DEFAULT_COMMAND },
                    ),
                )
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    connectionTesting = false,
                    diagnostic = page.diagnostic,
                    message = "Соединение работает. Изображений с промптами: ${page.items.size}",
                )
            }.onFailure { error ->
                val diagnostic = (error as? CivitaiApiException)?.diagnostic
                _uiState.value = _uiState.value.copy(
                    connectionTesting = false,
                    diagnostic = diagnostic ?: _uiState.value.diagnostic,
                    error = error.message ?: "Проверка соединения не удалась",
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }
}

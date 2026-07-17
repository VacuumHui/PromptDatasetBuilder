package com.promptdatasetbuilder.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.promptdatasetbuilder.app.data.AppSettings
import com.promptdatasetbuilder.app.data.DatasetEntry
import com.promptdatasetbuilder.app.data.DatasetExporter
import com.promptdatasetbuilder.app.data.NetworkDiagnostic
import com.promptdatasetbuilder.app.data.SourceImage
import com.promptdatasetbuilder.app.ui.AppViewModel
import com.promptdatasetbuilder.app.ui.theme.PromptDatasetTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PromptDatasetTheme {
                PromptDatasetApp(viewModel)
            }
        }
    }
}

private enum class MainTab { GALLERY, DATASET, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptDatasetApp(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.GALLERY) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    DatasetExporter(context.contentResolver).exportJsonl(uri, state.entries)
                }.onSuccess { count ->
                    snackbarHostState.showSnackbar("Экспортировано записей: $count")
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(error.message ?: "Ошибка экспорта")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.images.isEmpty()) viewModel.refreshGallery()
    }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            MainTab.GALLERY -> "Разметка промптов"
                            MainTab.DATASET -> "Датасет (${state.entries.size})"
                            MainTab.SETTINGS -> "Настройки"
                        },
                    )
                },
                actions = {
                    if (tab == MainTab.GALLERY) {
                        IconButton(onClick = viewModel::refreshGallery) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                    if (tab == MainTab.DATASET) {
                        IconButton(
                            enabled = state.entries.isNotEmpty(),
                            onClick = {
                                exportLauncher.launch(
                                    "prompt_dataset_${System.currentTimeMillis()}.jsonl",
                                )
                            },
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Экспорт JSONL")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.GALLERY,
                    onClick = { tab = MainTab.GALLERY },
                    icon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                    label = { Text("Галерея") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.DATASET,
                    onClick = {
                        viewModel.refreshEntries()
                        tab = MainTab.DATASET
                    },
                    icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    label = { Text("Датасет") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.SETTINGS,
                    onClick = { tab = MainTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                MainTab.GALLERY -> GalleryScreen(
                    images = state.images,
                    loading = state.loading,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.canLoadMore,
                    diagnostic = state.diagnostic,
                    onImageClick = viewModel::selectImage,
                    onLoadMore = viewModel::loadMore,
                    onRefresh = viewModel::refreshGallery,
                )
                MainTab.DATASET -> DatasetScreen(
                    entries = state.entries,
                    onDelete = viewModel::deleteEntry,
                    onExport = {
                        exportLauncher.launch(
                            "prompt_dataset_${System.currentTimeMillis()}.jsonl",
                        )
                    },
                )
                MainTab.SETTINGS -> SettingsScreen(
                    current = state.settings,
                    diagnostic = state.diagnostic,
                    testing = state.connectionTesting,
                    onSave = viewModel::saveSettings,
                    onTest = viewModel::testConnection,
                )
            }
        }
    }

    state.selectedImage?.let { image ->
        AnnotationDialog(
            image = image,
            command = state.settings.command,
            onDismiss = viewModel::closeAnnotation,
            onSkip = { viewModel.skipForSession(image.id) },
            onIgnore = { viewModel.ignorePermanently(image.id) },
            onSave = { input, output -> viewModel.saveEntry(image, input, output) },
        )
    }
}

@Composable
private fun GalleryScreen(
    images: List<SourceImage>,
    loading: Boolean,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    diagnostic: NetworkDiagnostic,
    onImageClick: (SourceImage) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        loading && images.isEmpty() -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        images.isEmpty() -> EmptyState(
            title = "Нет новых изображений с промптами",
            text = if (canLoadMore) {
                "На просмотренных страницах не нашлось подходящих записей. Перейдите дальше."
            } else {
                "Откройте настройки, выполните проверку соединения и посмотрите диагностику. " +
                    diagnostic.message
            },
            button = if (canLoadMore) "Следующая страница" else "Обновить",
            onClick = if (canLoadMore) onLoadMore else onRefresh,
        )

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(images, key = { it.id }) { image ->
                ImageCard(image, onClick = { onImageClick(image) })
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                        canLoadMore -> FilledTonalButton(onClick = onLoadMore) {
                            Text("Загрузить ещё")
                        }
                        else -> Text("Конец ленты", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageCard(image: SourceImage, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            AsyncImage(
                model = imageRequest(image.url),
                contentDescription = "Изображение ${image.id}",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        (image.width.toFloat() / image.height.toFloat()).coerceIn(0.65f, 1.5f),
                    ),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = image.nsfwLevel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.84f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        image.username?.let { username ->
            Text(
                text = username,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun imageRequest(url: String): ImageRequest = ImageRequest.Builder(LocalContext.current)
    .data(url)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.DISABLED)
    .networkCachePolicy(CachePolicy.ENABLED)
    .build()

@Composable
private fun AnnotationDialog(
    image: SourceImage,
    command: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onIgnore: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var input by rememberSaveable(image.id) { mutableStateOf("") }
    var output by rememberSaveable(image.id) { mutableStateOf(image.prompt) }
    var revealed by rememberSaveable(image.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val validInput = input.trim().length >= 5

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Создание записи", modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Другие действия")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Не показывать больше") },
                            onClick = {
                                menuExpanded = false
                                onIgnore()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = imageRequest(image.url),
                    contentDescription = "Выбранное изображение",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )

                Text("Команда", fontWeight = FontWeight.Bold)
                Text(
                    command,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ввод — ваше описание изображения") },
                    supportingText = {
                        Text("Сначала опишите картинку своими словами. Исходный промпт скрыт.")
                    },
                    minLines = 4,
                    maxLines = 10,
                )

                if (!revealed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Исходный промпт скрыт", fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                "Кнопка станет доступна после ввода хотя бы пяти символов.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { revealed = true }, enabled = validInput) {
                                Icon(Icons.Default.Visibility, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Показать исходный промпт")
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Вывод — исходный промпт") },
                        supportingText = { Text("Промпт можно исправить перед сохранением.") },
                        minLines = 6,
                        maxLines = 16,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = revealed && input.isNotBlank() && output.isNotBlank(),
                onClick = { onSave(input.trim(), output.trim()) },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Пропустить")
                }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

@Composable
private fun DatasetScreen(
    entries: List<DatasetEntry>,
    onDelete: (Long) -> Unit,
    onExport: () -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(
            title = "Датасет пока пуст",
            text = "Выберите изображение, введите своё описание и сохраните запись.",
            button = null,
            onClick = {},
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${entries.size} записей",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "JSONL: command, input, output",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalButton(onClick = onExport) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Экспорт")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                DatasetEntryCard(entry, onDelete = { onDelete(entry.id) })
            }
        }
    }
}

@Composable
private fun DatasetEntryCard(entry: DatasetEntry, onDelete: () -> Unit) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.input,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(entry.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить")
                }
            }

            if (expanded) {
                HorizontalDivider()
                FieldLabel("COMMAND")
                SelectionContainer { Text(entry.command) }
                FieldLabel("INPUT")
                SelectionContainer { Text(entry.input) }
                FieldLabel("OUTPUT")
                SelectionContainer { Text(entry.output) }
                Text(
                    "Источник: ${entry.sourceImageId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = {
                Text("После удаления изображение сможет появиться в галерее снова.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsScreen(
    current: AppSettings,
    diagnostic: NetworkDiagnostic,
    testing: Boolean,
    onSave: (AppSettings) -> Unit,
    onTest: (AppSettings) -> Unit,
) {
    var apiKey by remember(current.apiKey) { mutableStateOf(current.apiKey) }
    var includeNsfw by rememberSaveable(current.includeNsfw) {
        mutableStateOf(current.includeNsfw)
    }
    var command by rememberSaveable(current.command) { mutableStateOf(current.command) }
    var pageSizeText by rememberSaveable(current.pageSize) {
        mutableStateOf(current.pageSize.toString())
    }
    var ageConfirmed by rememberSaveable(current.ageConfirmed) {
        mutableStateOf(current.ageConfirmed)
    }
    var showApiKey by rememberSaveable { mutableStateOf(false) }

    val draft = AppSettings(
        apiKey = apiKey,
        includeNsfw = includeNsfw,
        command = command,
        pageSize = pageSizeText.toIntOrNull()?.coerceIn(10, 100) ?: 30,
        ageConfirmed = ageConfirmed,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Источник",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Официальный Civitai REST API", fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        AppSettings.API_ENDPOINT,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Адрес зафиксирован в приложении. civita.red больше не используется, " +
                        "потому что он возвращал HTML вместо API-данных.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-ключ Civitai") },
            visualTransformation = if (showApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showApiKey = !showApiKey }) {
                    Icon(
                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Показать или скрыть ключ",
                    )
                }
            },
            supportingText = {
                Text("Ключ хранится локально через Android Keystore и не попадает в GitHub.")
            },
            singleLine = true,
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Показывать NSFW", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Для включения нужны API-ключ и подтверждение возраста.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = includeNsfw,
                        onCheckedChange = { includeNsfw = it },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Мне исполнилось 18 лет")
                    }
                    Switch(
                        checked = ageConfirmed,
                        onCheckedChange = { ageConfirmed = it },
                    )
                }
            }
        }

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Команда для датасета") },
            minLines = 3,
            maxLines = 6,
        )

        OutlinedTextField(
            value = pageSizeText,
            onValueChange = { pageSizeText = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Изображений в одном запросе: 10–100") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onTest(draft) },
                enabled = !testing,
                modifier = Modifier.weight(1f),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Проверить API")
                }
            }
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
        }

        DiagnosticCard(diagnostic)

        Text(
            "Версия приложения: ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticCard(diagnostic: NetworkDiagnostic) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Диагностика соединения", fontWeight = FontWeight.SemiBold)
            }
            Text("Состояние: ${diagnostic.message}")
            Text("HTTP: ${diagnostic.httpStatus ?: "—"}")
            Text("Content-Type: ${diagnostic.contentType ?: "—"}")
            Text("Элементов в ответе: ${diagnostic.parsedItems ?: "—"}")
            Text("С промптами: ${diagnostic.itemsWithPrompt ?: "—"}")
            Text("Получено байт: ${diagnostic.receivedBytes ?: "—"}")
            SelectionContainer {
                Text(
                    diagnostic.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    text: String,
    button: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.AddPhotoAlternate,
            contentDescription = null,
            modifier = Modifier.size(70.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        button?.let {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onClick) { Text(it) }
        }
    }
}

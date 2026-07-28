package com.openminis.app.ui.bookshelf

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.openminis.app.data.db.BookSourceEntity
import com.openminis.app.data.repository.BookSourceRepository
import com.openminis.app.data.repository.RemoteBook
import com.openminis.app.data.repository.SourceChapter
import com.openminis.app.sandbox.PRootKernel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.data.imports.TxtTocRules
import com.openminis.app.data.repository.BookRepository
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.util.UUID

/**
 * Bookshelf main screen — shows all book projects as a grid.
 * Styled to match OpenMinis SessionListScreen look & feel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBookClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(BookRepository.listBooks(context)) }
    // Source-cached books live in the same /var/minis/books dir but should not
    // appear in the "我的书" grid (they're reached via the 书源 tab instead).
    val myBooks = books.filter { it.kind != "source-cache" }
    var showNewBookDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    // TXT import state. pickedUri triggers the import dialog; importing drives
    // the progress indicator; importError surfaces a failure message.
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0 to 0) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Book-source UI state. Sources are remote (Room-backed) and shown on a
    // separate "书源" tab; selecting one opens its live book list.
    var tab by remember { mutableStateOf(0) } // 0 = 我的书, 1 = 书源
    var sources by remember { mutableStateOf<List<BookSourceEntity>>(emptyList()) }
    var selectedSource by remember { mutableStateOf<BookSourceEntity?>(null) }
    var showImportSourceDialog by remember { mutableStateOf(false) }

    // Cached source-book navigation state. Clicking a book in a source's list
    // materialises a local cache, then opens its detail screen.
    var selectedCachedBook by remember { mutableStateOf<String?>(null) }
    var cachingBook by remember { mutableStateOf(false) }

    fun refreshSources() {
        scope.launch {
            sources = withContext(Dispatchers.IO) { BookSourceRepository.listSources(context) }
        }
    }
    LaunchedEffect(Unit) { refreshSources() }

    val txtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pickedUri = uri
    }

    fun refreshBooks() {
        books = BookRepository.listBooks(context)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("书架", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)
                    },
                    actions = {
                        // Import a TXT file -> split into chapters -> new book.
                        if (tab == 0) {
                            IconButton(onClick = {
                                txtLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                            }) {
                                Icon(Icons.Outlined.FileUpload, contentDescription = "导入 TXT")
                            }
                        }
                    },
                )
                if (selectedSource == null) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text("我的书") },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text("书源") },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedSource == null) {
                FloatingActionButton(
                    onClick = {
                        if (tab == 1) showImportSourceDialog = true else showNewBookDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = if (tab == 1) "导入书源" else "新建书",
                    )
                }
            }
        },
    ) { padding ->
        when {
            cachingBook ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            selectedCachedBook != null ->
                SourceBookDetailScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    bookId = selectedCachedBook!!,
                    onBack = { selectedCachedBook = null },
                    context = context,
                )
            selectedSource != null ->
                BookSourceBooksContent(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    source = selectedSource!!,
                    onBack = { selectedSource = null },
                    onBookClick = { bookUrl ->
                        cachingBook = true
                        scope.launch {
                            val id = withContext(Dispatchers.IO) {
                                BookSourceRepository.cacheBookInfo(selectedSource!!, bookUrl, context)
                            }
                            cachingBook = false
                            if (id != null) {
                                selectedCachedBook = id
                            } else {
                                importError = "缓存失败：无法获取该书详情/目录，请检查书源是否可用。"
                            }
                        }
                    },
                    context = context,
                )
            tab == 1 ->
                SourcesContent(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    sources = sources,
                    onSourceClick = { selectedSource = it },
                    onImportClick = { showImportSourceDialog = true },
                    onDelete = { url ->
                        scope.launch {
                            withContext(Dispatchers.IO) { BookSourceRepository.deleteSource(url, context) }
                            refreshSources()
                        }
                    },
                )
            importing -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        val (done, total) = importProgress
                        Text(
                            if (total > 0) "正在导入章节 $done / $total" else "正在读取并分章…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            myBooks.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.height(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "还没有书",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击 + 创建你的第一本小说",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    items(myBooks, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onClick = { onBookClick(book.id) },
                            onDelete = { showDeleteConfirm = book.id },
                        )
                    }
                }
            }
        }
    }

    // New book dialog
    if (showNewBookDialog) {
        NewBookDialog(
            onDismiss = { showNewBookDialog = false },
            onConfirm = { title, genre, synopsis ->
                val bookId = UUID.randomUUID().toString().take(8)
                BookRepository.createBook(bookId, title, genre, synopsis, context)
                refreshBooks()
                showNewBookDialog = false
                onBookClick(bookId)
            },
        )
    }

    // TXT import dialog. Picks title / split rule / encoding, then runs the
    // (potentially slow) split + write on IO, surfacing progress.
    pickedUri?.let { uri ->
        ImportBookDialog(
            uri = uri,
            onDismiss = {
                pickedUri = null
                importError = null
            },
            onConfirm = { title, ruleIndex, charset ->
                // ruleIndex -1 / charset null => auto-detect inside importBook.
                val regex: String? = if (ruleIndex < 0) null else TxtTocRules.presets[ruleIndex].regex
                importing = true
                importProgress = 0 to 0
                pickedUri = null
                scope.launch {
                    val newId = withContext(Dispatchers.IO) {
                        BookRepository.importBook(
                            title = title,
                            sourceUri = uri,
                            regex = regex,
                            charset = charset,
                            context = context,
                            progress = { done, total -> importProgress = done to total },
                        )
                    }
                    importing = false
                    if (newId != null) {
                        refreshBooks()
                        onBookClick(newId)
                    } else {
                        importError = "导入失败：无法读取文件或分章未产生章节。"
                    }
                }
            },
        )
    }

    // Import failure notice
    importError?.let { msg ->
        MinisAlertDialog(
            onDismissRequest = { importError = null },
            title = "导入失败",
            text = msg,
            confirmText = "知道了",
            onConfirm = { importError = null },
        )
    }

    // Book-source import dialog (paste legado JSON or a URL)
    if (showImportSourceDialog) {
        ImportSourceDialog(
            onDismiss = { showImportSourceDialog = false },
            onConfirm = { text ->
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        runCatching {
                            val t = text.trim()
                            if (t.startsWith("http://") || t.startsWith("https://"))
                                BookSourceRepository.importFromUrl(t, context)
                            else
                                BookSourceRepository.importFromText(t, context)
                        }.getOrNull() ?: emptyList()
                    }
                    showImportSourceDialog = false
                    if (imported.isEmpty()) {
                        importError = "导入失败：无法解析书源 JSON，或 URL 无法访问。"
                    } else {
                        refreshSources()
                    }
                }
            },
        )
    }

    // Delete confirmation
    showDeleteConfirm?.let { bookId ->
        val book = myBooks.find { it.id == bookId }
        MinisAlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = "删除书",
            text = "确定删除「${book?.title ?: bookId}」吗？此操作不可恢复。",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                BookRepository.deleteBook(bookId, context)
                refreshBooks()
                showDeleteConfirm = null
            },
        )
    }
}

@Composable
private fun BookCard(
    book: BookRepository.MiniBook,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val coverColor = bookCoverColor(book.genre)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Column {
            // Cover area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(coverColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Book,
                    contentDescription = null,
                    modifier = Modifier.height(48.dp),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            // Info area
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${book.totalWords}字 | ${book.currentChapter}章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (book.genre.isNotBlank()) {
                    Text(
                        book.genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBookDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, genre: String, synopsis: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var synopsis by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("新建小说", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text("书名", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                DialogTextField(value = title, onValueChange = { title = it }, placeholder = "输入书名")
                Spacer(Modifier.height(12.dp))
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                DialogTextField(value = genre, onValueChange = { genre = it }, placeholder = "玄幻/科幻/言情/悬疑/...")
                Spacer(Modifier.height(12.dp))
                Text("简介", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                DialogTextField(value = synopsis, onValueChange = { synopsis = it }, placeholder = "可选：故事简介", singleLine = false, maxLines = 3)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    com.openminis.app.ui.components.MinisTextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    com.openminis.app.ui.components.MinisTextButton(onClick = {
                        if (title.isNotBlank()) onConfirm(title.trim(), genre.trim(), synopsis.trim())
                    }) {
                        Text("创建", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Import-from-TXT dialog. Lets the user set a book title, pick a chapter-split
 * rule (from [TxtTocRules.presets]) and a text encoding, then confirms. The
 * actual file read + split runs off the UI thread by the caller (see
 * [BookshelfScreen]'s onConfirm handler).
 */
@Composable
private fun ImportBookDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (title: String, ruleIndex: Int, charset: Charset?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    // -1 = 智能识别（自动挑选最匹配的分章规则）— the recommended default.
    var ruleIndex by remember { mutableStateOf(-1) }
    // -1 = 自动检测编码（UTF-8/GBK 嗅探）— the recommended default.
    var charsetIndex by remember { mutableStateOf(-1) }
    var rulesExpanded by remember { mutableStateOf(false) }
    var charsetExpanded by remember { mutableStateOf(false) }

    val charsets: List<Pair<java.nio.charset.Charset, String>> = listOf(
        Charsets.UTF_8 to "UTF-8",
        java.nio.charset.Charset.forName("GBK") to "GBK",
        java.nio.charset.Charset.forName("GB18030") to "GB18030",
        Charsets.ISO_8859_1 to "ISO-8859-1",
    )
    val currentRuleName = if (ruleIndex < 0) "智能识别（推荐）" else TxtTocRules.presets[ruleIndex].name
    val currentCharset: Charset? = if (charsetIndex < 0) null else charsets[charsetIndex].first
    val currentCharsetName = if (charsetIndex < 0) "自动检测（推荐）" else charsets[charsetIndex].second

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("导入 TXT", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                Text("书名", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                DialogTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "输入书名（留空则用文件名）",
                )

                Spacer(Modifier.height(12.dp))
                Text("分章规则", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Box {
                    com.openminis.app.ui.components.MinisTextButton(
                        onClick = { rulesExpanded = true },
                    ) {
                        Text(currentRuleName)
                    }
                    DropdownMenu(
                        expanded = rulesExpanded,
                        onDismissRequest = { rulesExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("智能识别（推荐）")
                                    Text(
                                        "自动试跑全部规则，选命中最多的一条",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                ruleIndex = -1
                                rulesExpanded = false
                            },
                        )
                        TxtTocRules.presets.forEachIndexed { i, rule ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(rule.name)
                                        Text(
                                            rule.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    ruleIndex = i
                                    rulesExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("编码", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Box {
                    com.openminis.app.ui.components.MinisTextButton(
                        onClick = { charsetExpanded = true },
                    ) {
                        Text(currentCharsetName)
                    }
                    DropdownMenu(
                        expanded = charsetExpanded,
                        onDismissRequest = { charsetExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("自动检测（推荐）") },
                            onClick = {
                                charsetIndex = -1
                                charsetExpanded = false
                            },
                        )
                        charsets.forEachIndexed { i, (_, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    charsetIndex = i
                                    charsetExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：大文件分章可能需要几秒。若章节切分不理想，可换一条规则重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    com.openminis.app.ui.components.MinisTextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    com.openminis.app.ui.components.MinisTextButton(onClick = {
                        // Default the title to the URI's last path segment if blank.
                        val resolvedTitle = title.trim().ifBlank {
                            uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "导入小说"
                        }
                        onConfirm(resolvedTitle, ruleIndex, currentCharset)
                    }) {
                        Text("导入", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Generate a deterministic color from genre name. */
private fun bookCoverColor(genre: String): Color {
    val palette = listOf(
        Color(0xFF2E8B8B), // teal (matches OpenMinis primary)
        Color(0xFF3478F6), // blue
        Color(0xFF5856D6), // indigo
        Color(0xFF30B0C7), // cyan
        Color(0xFFF09A37), // orange
        Color(0xFFFF2D55), // red
        Color(0xFF34C759), // green
        Color(0xFF9B59B6), // purple
    )
    val idx = genre.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) } % palette.size
    return palette[idx]
}

// ── Book-source UI (legado-format remote sources) ──────────────────────────

@Composable
private fun SourcesContent(
    modifier: Modifier = Modifier,
    sources: List<BookSourceEntity>,
    onSourceClick: (BookSourceEntity) -> Unit,
    onImportClick: () -> Unit,
    onDelete: (String) -> Unit,
) {
    if (sources.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.AutoStories,
                    contentDescription = null,
                    modifier = Modifier.height(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text("还没有书源", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("点 + 导入书源（legado 格式 JSON 或 URL）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    } else {
        LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sources, key = { it.bookSourceUrl }) { src ->
                SourceCard(src, onClick = { onSourceClick(src) }, onDelete = { onDelete(src.bookSourceUrl) })
            }
        }
    }
}

@Composable
private fun SourceCard(source: BookSourceEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val color = bookCoverColor(source.bookSourceGroup ?: source.bookSourceName)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.height(28.dp), tint = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(source.bookSourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!source.bookSourceGroup.isNullOrBlank()) {
                    Text(source.bookSourceGroup!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(if (source.enabledExplore) "可探索" else "不可探索", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除书源", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BookSourceBooksContent(
    modifier: Modifier = Modifier,
    source: BookSourceEntity,
    onBack: () -> Unit,
    onBookClick: (String) -> Unit,
    context: Context,
) {
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<RemoteBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    val categories = BookSourceRepository.categories(source)
    var categoryIndex by remember { mutableStateOf(0) }

    fun load(reset: Boolean) {
        scope.launch {
            loading = true
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    BookSourceRepository.exploreBooks(
                        source,
                        if (reset) 0 else categoryIndex,
                        if (reset) 1 else page,
                        context,
                    )
                }.getOrDefault(emptyList())
            }
            loading = false
            if (reset) {
                books = next
                page = 2
            } else {
                books = books + next
                page++
            }
        }
    }

    LaunchedEffect(categoryIndex) { load(true) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") }
            Text(
                source.bookSourceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (categories.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories.indices.toList()) { i ->
                    val selected = i == categoryIndex
                    com.openminis.app.ui.components.MinisTextButton(onClick = { categoryIndex = i }) {
                        Text(
                            categories[i],
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        when {
            loading && books.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            books.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该分类暂无书", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else ->
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(books, key = { it.bookUrl }) { b -> BookRow(b, onClick = { onBookClick(b.bookUrl) }) }
                    if (loading) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            com.openminis.app.ui.components.MinisTextButton(onClick = { load(false) }) {
                                Text("加载更多", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun BookRow(book: RemoteBook, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(bookCoverColor(book.kind)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.height(24.dp), tint = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(book.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    book.author.takeIf { it.isNotBlank() },
                    book.kind.takeIf { it.isNotBlank() },
                    book.lastChapter.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ImportSourceDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("导入书源", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text("粘贴书源 JSON（单条或数组）或书源 URL", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                DialogTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "https://.../source.json  或  [{...}]",
                    singleLine = false,
                    maxLines = 6,
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    com.openminis.app.ui.components.MinisTextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    com.openminis.app.ui.components.MinisTextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                        Text("导入", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Detail screen for a cached source book. Mirrors legado's on-demand model:
 * the book info + TOC are already cached locally (fetched when the book was
 * clicked in [BookSourceBooksContent]); each chapter body is lazily fetched
 * the first time it is opened, then persisted under chapters/ and re-read from
 * disk afterwards. A "导出" action bundles the cached chapters into one .txt.
 */
@Composable
private fun SourceBookDetailScreen(
    modifier: Modifier = Modifier,
    bookId: String,
    onBack: () -> Unit,
    context: Context,
) {
    val scope = rememberCoroutineScope()
    val book = remember { BookRepository.loadBook(bookId, context) }
    var chapters by remember { mutableStateOf<List<SourceChapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var readingNum by remember { mutableStateOf<Int?>(null) }
    var readingContent by remember { mutableStateOf<String?>(null) }
    var loadingChapter by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        loading = true
        chapters = withContext(Dispatchers.IO) { BookSourceRepository.listSourceChapters(bookId, context) }
        loading = false
    }

    fun openChapter(num: Int) {
        loadingChapter = true
        readingNum = num
        scope.launch {
            val text = withContext(Dispatchers.IO) { BookSourceRepository.readSourceChapter(bookId, num, context) }
            loadingChapter = false
            readingContent = text
        }
    }

    fun doExport() {
        scope.launch {
            val chs = withContext(Dispatchers.IO) { BookSourceRepository.listSourceChapters(bookId, context) }
            val sb = StringBuilder()
            sb.appendLine("# ${book?.title ?: "book"}")
            sb.appendLine()
            var exported = 0
            var skipped = 0
            for (c in chs) {
                if (!c.cached) { skipped++; continue }
                val text = withContext(Dispatchers.IO) { BookRepository.readChapter(bookId, c.num, context) } ?: continue
                val body = text.lines().dropWhile { it.startsWith("# ") }.drop(1).joinToString("\n").trimStart('\n')
                sb.appendLine("## 第${c.num}章")
                sb.appendLine(body)
                sb.appendLine()
                exported++
            }
            val hostDir = PRootKernel.resolveSessionHostPath("", BookRepository.booksBasePath(), context)
                ?.let { java.io.File(it, bookId) }
            val safe = (book?.title ?: "book").filter { it.isLetterOrDigit() || it in "_-" }.ifBlank { "book" }
            val outFile = hostDir?.let {
                java.io.File(java.io.File(it, "export").also { d -> d.mkdirs() }, "$safe.txt")
            }
            outFile?.writeText(sb.toString())
            exportResult = if (outFile != null) {
                "已导出 $exported 章到：\n${outFile.absolutePath}" +
                    if (skipped > 0) "\n($skipped 章尚未缓存，已跳过——先点开这些章再导出)" else ""
            } else {
                "导出失败：无法解析书籍目录"
            }
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") }
            Text(
                book?.title ?: bookId,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { doExport() }) {
                Text("导出", color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val sub = "共 ${chapters.size} 章 · 已缓存 ${chapters.count { it.cached }}"
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!book?.synopsis.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    book?.synopsis ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            chapters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无章节", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chapters, key = { it.num }) { ch ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        onClick = { openChapter(ch.num) },
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ch.cached) {
                                Icon(Icons.Outlined.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(ch.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    // Reader dialog (full-screen-ish).
    if (readingNum != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { readingNum = null; readingContent = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                        IconButton(onClick = { readingNum = null; readingContent = null }) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "关闭")
                        }
                        Text(
                            "第${readingNum}章",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (loadingChapter) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            item { Text(readingContent ?: "（空）", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }

    exportResult?.let { msg ->
        MinisAlertDialog(
            onDismissRequest = { exportResult = null },
            title = "导出完成",
            text = msg,
            confirmText = "知道了",
            onConfirm = { exportResult = null },
        )
    }
}

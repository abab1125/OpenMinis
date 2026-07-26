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
    var showNewBookDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    // TXT import state. pickedUri triggers the import dialog; importing drives
    // the progress indicator; importError surfaces a failure message.
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0 to 0) }
    var importError by remember { mutableStateOf<String?>(null) }

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
            TopAppBar(
                title = {
                    Text("书架", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)
                },
                actions = {
                    // Import a TXT file -> split into chapters -> new book.
                    IconButton(onClick = {
                        txtLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                    }) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "导入 TXT")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewBookDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建书")
            }
        },
    ) { padding ->
        if (importing) {
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
        } else if (books.isEmpty()) {
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
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                        onDelete = { showDeleteConfirm = book.id },
                    )
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
                val regex = TxtTocRules.presets[ruleIndex].regex
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

    // Delete confirmation
    showDeleteConfirm?.let { bookId ->
        val book = books.find { it.id == bookId }
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
    onConfirm: (title: String, ruleIndex: Int, charset: Charset) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var ruleIndex by remember { mutableStateOf(TxtTocRules.DEFAULT_INDEX) }
    var charsetIndex by remember { mutableStateOf(0) }
    var rulesExpanded by remember { mutableStateOf(false) }
    var charsetExpanded by remember { mutableStateOf(false) }

    val charsets = listOf(Charsets.UTF_8 to "UTF-8", Charsets.GBK to "GBK", Charsets.ISO_8859_1 to "ISO-8859-1")
    val currentRule = TxtTocRules.presets[ruleIndex]
    val currentCharset = charsets[charsetIndex].first

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
                        Text(currentRule.name)
                    }
                    DropdownMenu(
                        expanded = rulesExpanded,
                        onDismissRequest = { rulesExpanded = false },
                    ) {
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
                        Text(currentCharset.name())
                    }
                    DropdownMenu(
                        expanded = charsetExpanded,
                        onDismissRequest = { charsetExpanded = false },
                    ) {
                        charsets.forEachIndexed { i, (cs, label) ->
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

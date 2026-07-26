package com.openminis.app.ui.bookshelf

import android.content.Context
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
import androidx.compose.material.icons.outlined.Stories
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.openminis.app.data.repository.BookRepository
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.DialogTextField
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
    var books by remember { mutableStateOf(BookRepository.listBooks(context)) }
    var showNewBookDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("书架", fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)
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
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Stories,
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
                books = BookRepository.listBooks(context)
                showNewBookDialog = false
                onBookClick(bookId)
            },
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
                books = BookRepository.listBooks(context)
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

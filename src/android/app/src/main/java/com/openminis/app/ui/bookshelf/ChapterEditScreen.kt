package com.openminis.app.ui.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.BookRepository

/**
 * In-place chapter editor: a title field + a body text field, saved back via
 * [BookRepository.writeChapter]. It does NOT go through the AI writing loop,
 * so the reader's Edit button is always functional even with no local model
 * or Hermes configured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditScreen(
    bookId: String,
    chapterNum: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val raw = remember(chapterNum) { BookRepository.readChapter(bookId, chapterNum, context) ?: "" }
    val (initialTitle, initialBody) = remember(raw) {
        val lines = raw.lines()
        if (lines.firstOrNull()?.startsWith("# ") == true) {
            lines.first().removePrefix("# ").trim() to lines.drop(1).joinToString("\n").trimStart()
        } else {
            "" to raw
        }
    }

    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf(initialBody) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑章节 · 第 $chapterNum 章") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        BookRepository.writeChapter(
                            bookId = bookId,
                            num = chapterNum,
                            title = title.trim().ifEmpty { null },
                            content = body,
                            append = false,
                            context = context,
                        )
                        onBack()
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("章节标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("正文") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 20,
                maxLines = Int.MAX_VALUE,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

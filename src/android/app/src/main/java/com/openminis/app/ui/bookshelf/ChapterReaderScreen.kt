package com.openminis.app.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.data.repository.BookRepository
import com.openminis.app.ui.markdown.MarkdownText

/**
 * Chapter reader — scrollable Markdown body text with left/right swipe to
 * navigate between chapters. Uses OpenMinis's built-in MarkdownText renderer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReaderScreen(
    bookId: String,
    initialChapterNum: Int,
    onBack: () -> Unit,
    onEditChapter: (Int) -> Unit,
) {
    val context = LocalContext.current
    var chapters by remember(bookId) { mutableStateOf(BookRepository.listChapters(bookId, context)) }

    // Refresh the chapter list + each body when returning from the editor
    // (ON_RESUME), so edits saved in ChapterEditScreen show up immediately
    // instead of being stale behind the remember() cache.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(bookId, resumeTick) {
        chapters = BookRepository.listChapters(bookId, context)
    }

    val initialPage = chapters.indexOfFirst { it.num == initialChapterNum }.coerceAtLeast(0)

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { chapters.size },
    )

    val currentChapter = chapters.getOrNull(pagerState.currentPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentChapter?.title ?: "阅读",
                            fontWeight = FontWeight.Bold,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            maxLines = 1,
                        )
                        if (chapters.isNotEmpty()) {
                            Text(
                                "${pagerState.currentPage + 1} / ${chapters.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { currentChapter?.let { onEditChapter(it.num) } }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑此章")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (chapters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无章节", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            val ch = chapters[page]
            var content by remember(ch.num) { mutableStateOf(BookRepository.readChapter(bookId, ch.num, context) ?: "") }
            // Re-read the body on resume so saved edits appear at once.
            LaunchedEffect(ch.num, resumeTick) {
                content = BookRepository.readChapter(bookId, ch.num, context) ?: ""
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // Chapter word count
                Text(
                    "${ch.wordCount}字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))

                // Body text rendered via OpenMinis's MarkdownText. Use
                // fillMaxWidth (not fillMaxSize) so the body grows to its
                // full content height and the outer verticalScroll can
                // actually scroll it — fillMaxSize would clamp the body to
                // the viewport and clip everything past the first screen.
                MarkdownText(
                    markdown = content,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

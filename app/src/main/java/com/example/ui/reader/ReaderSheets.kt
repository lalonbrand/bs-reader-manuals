package com.example.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.BookEntity
import com.example.data.local.ReaderSurfaceTone
import com.example.data.local.TranslatedLine
import com.example.ui.bookshelf.formatBytes
import com.example.ui.theme.NotoBengaliFont
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.glassPanel
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageGridBottomSheet(
    pageFiles: List<File>,
    currentPageIndex: Int,
    pageAspectRatio: Float,
    loadThumbnail: suspend (File) -> ImageBitmap?,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Extracted Folio Sheets (${pageFiles.size})",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap any pre-rasterized WebP plate to jump immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                itemsIndexed(pageFiles, key = { _, file -> file.name }) { index, file ->
                    val isCurrent = index == currentPageIndex
                    val thumb by produceState<ImageBitmap?>(initialValue = null, key1 = file.absolutePath) {
                        value = loadThumbnail(file)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .testTag("thumbnail_page_$index")
                            .clickable {
                                onSelectPage(index)
                                onDismiss()
                            }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = if (isCurrent) 2.5.dp else 1.dp,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(pageAspectRatio)
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb!!,
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${index + 1} • ${file.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceBottomSheet(
    book: BookEntity?,
    surfaceTone: ReaderSurfaceTone,
    showPageOverlay: Boolean,
    pageSoundEnabled: Boolean,
    currentPageFile: File?,
    onSelectTone: (ReaderSurfaceTone) -> Unit,
    onTogglePageOverlay: (Boolean) -> Unit,
    onTogglePageSound: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Reading Surface & Telemetry",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PAPER TONE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReaderSurfaceTone.entries.forEach { tone ->
                        FilterChip(
                            selected = surfaceTone == tone,
                            onClick = { onSelectTone(tone) },
                            label = { Text(tone.label) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tone_chip_${tone.name}")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WebP File Telemetry Pill",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Display active filename and byte size in reader",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showPageOverlay,
                    onCheckedChange = onTogglePageOverlay,
                    modifier = Modifier.testTag("overlay_toggle_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Soft Page-Turn Sound",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Subtle paper sound effect when turning pages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pageSoundEnabled,
                    onCheckedChange = onTogglePageSound,
                    modifier = Modifier.testTag("page_sound_toggle_switch")
                )
            }

            if (book != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "LOCAL WEBP DIRECTORY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = book.localDirPath ?: "Unavailable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (currentPageFile != null) {
                            Text(
                                text = "Active plate: ${currentPageFile.name} (${formatBytes(currentPageFile.length())})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilingualTranslationBottomSheet(
    currentPageIndex: Int,
    totalPages: Int,
    translatedPagesCount: Int,
    hasBookTranslations: Boolean,
    pageLines: List<TranslatedLine>,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var searchQuery by remember { mutableStateOf("") }
    var banglaOnlyMode by remember { mutableStateOf(true) }
    var copiedIndex by remember { mutableStateOf<Int?>(null) }
    var copiedAllPage by remember { mutableStateOf(false) }

    LaunchedEffect(copiedIndex) {
        if (copiedIndex != null) {
            delay(1500L)
            copiedIndex = null
        }
    }

    LaunchedEffect(copiedAllPage) {
        if (copiedAllPage) {
            delay(1500L)
            copiedAllPage = false
        }
    }

    val filteredParagraphs = remember(pageLines, searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.isBlank()) {
            pageLines
        } else {
            pageLines.filter { para ->
                para.en.contains(trimmed, ignoreCase = true) ||
                    para.bn.contains(trimmed, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with page stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bangla_sheet_title),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = NotoBengaliFont),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasBookTranslations) {
                            val unit = if (pageLines.size == 1) "paragraph" else "paragraphs"
                            "Page ${currentPageIndex + 1} of $totalPages • ${pageLines.size} $unit"
                        } else {
                            "Page ${currentPageIndex + 1} of $totalPages"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = SoftCyan
                    )
                    Text(
                        text = "$translatedPagesCount / $totalPages pages translated offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("translated_pages_count_label")
                    )
                }

                if (totalPages > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val canPrev = currentPageIndex > 0
                        val canNext = currentPageIndex < totalPages - 1

                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = if (canPrev) 0.12f else 0.04f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = if (canPrev) 0.24f else 0.10f))
                        ) {
                            IconButton(
                                onClick = { onSelectPage(currentPageIndex - 1) },
                                enabled = canPrev,
                                modifier = Modifier
                                    .testTag("bangla_prev_page_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous page translation",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = if (canNext) 0.12f else 0.04f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = if (canNext) 0.24f else 0.10f))
                        ) {
                            IconButton(
                                onClick = { onSelectPage(currentPageIndex + 1) },
                                enabled = canNext,
                                modifier = Modifier
                                    .testTag("bangla_next_page_button")
                                    .minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next page translation",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            if (hasBookTranslations && pageLines.isNotEmpty()) {
                // Display mode toggle + Copy Page chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = banglaOnlyMode,
                            onClick = { banglaOnlyMode = true },
                            label = {
                                Text(
                                    text = "শুধু বাংলা",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = NotoBengaliFont)
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("mode_bangla_only_chip")
                        )

                        FilterChip(
                            selected = !banglaOnlyMode,
                            onClick = { banglaOnlyMode = false },
                            label = {
                                Text(
                                    text = "EN + বাংলা",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = NotoBengaliFont)
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("mode_bilingual_chip")
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (copiedAllPage) SoftCyan.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(
                            1.dp,
                            if (copiedAllPage) SoftCyan.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .testTag("copy_page_translation_button")
                            .clickable {
                                val payload = filteredParagraphs.joinToString("\n\n") { para ->
                                    if (banglaOnlyMode) para.bn else "${para.en}\n${para.bn}"
                                }
                                clipboardManager.setText(AnnotatedString(payload))
                                copiedAllPage = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy page translation",
                                tint = if (copiedAllPage) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (copiedAllPage) "Copied" else "Copy Page",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (copiedAllPage) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (pageLines.size > 4 || searchQuery.isNotBlank()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search English or বাংলা on this page...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear filter")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedBorderColor = SoftCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.18f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bangla_search_input")
                    )
                }
            }

            when {
                !hasBookTranslations -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .glassPanel(shape = RoundedCornerShape(20.dp))
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(SoftCyan.copy(alpha = 0.14f), CircleShape)
                                .border(1.dp, SoftCyan.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = SoftCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Offline Bangla Translation Not Bundled Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "This downloaded manual does not include translations_bn.json yet. Once GitHub Actions converts this manual with GEMINI_API_KEY, tap Re-download High Quality on the bookshelf to get offline paragraph-by-paragraph Bangla.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                pageLines.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                            .glassPanel(shape = RoundedCornerShape(20.dp))
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No Translatable Text on Page ${currentPageIndex + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "This page appears to be an image, diagram, or blank separator.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                filteredParagraphs.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                            .glassPanel(shape = RoundedCornerShape(18.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No paragraphs match \"$searchQuery\" on this page.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                banglaOnlyMode -> {
                    // Clean flowing paragraph-style document reader
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(430.dp)
                            .glassPanel(shape = RoundedCornerShape(20.dp))
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(
                                filteredParagraphs,
                                key = { idx, item -> "bn_${idx}_${item.en.hashCode()}" }
                            ) { idx, paragraph ->
                                val isCopied = copiedIndex == idx
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCopied) SoftCyan.copy(alpha = 0.14f)
                                            else Color.Transparent
                                        )
                                        .testTag("translation_paragraph_card_$idx")
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(paragraph.bn))
                                            copiedIndex = idx
                                        }
                                        .padding(horizontal = if (isCopied) 10.dp else 2.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = paragraph.bn,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = NotoBengaliFont,
                                            fontSize = 16.sp,
                                            lineHeight = 27.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isCopied) {
                                        Text(
                                            text = "Copied paragraph",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SoftCyan
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(430.dp),
                        contentPadding = PaddingValues(bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            filteredParagraphs,
                            key = { idx, item -> "bi_${idx}_${item.en.hashCode()}" }
                        ) { idx, paragraph ->
                            val isCopied = copiedIndex == idx
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassPanel(shape = RoundedCornerShape(18.dp), highlighted = isCopied)
                                    .testTag("translation_paragraph_card_$idx")
                                    .clickable {
                                        clipboardManager.setText(
                                            AnnotatedString("${paragraph.en}\n\n${paragraph.bn}")
                                        )
                                        copiedIndex = idx
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Paragraph ${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SoftCyan
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy paragraph translation",
                                                tint = if (isCopied) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (isCopied) "Copied" else "Tap to copy",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isCopied) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Text(
                                        text = paragraph.en,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                                    Text(
                                        text = paragraph.bn,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = NotoBengaliFont,
                                            fontSize = 16.sp,
                                            lineHeight = 27.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

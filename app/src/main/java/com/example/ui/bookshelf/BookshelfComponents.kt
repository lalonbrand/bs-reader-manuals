package com.example.ui.bookshelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.BookEntity
import com.example.data.local.BookStatus
import com.example.data.repository.TransferProgress
import java.io.File

@Composable
fun FeaturedBookSpotlightCard(
    book: BookEntity,
    onOpenBook: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pageTotal = book.pageCount.coerceAtLeast(1)
    val currentDisplay = (book.lastReadPage + 1).coerceIn(1, pageTotal)
    val progress = currentDisplay.toFloat() / pageTotal.toFloat()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("featured_book_card")
            .clickable(onClick = onOpenBook),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverArt(
                localCoverPath = book.localCoverPath,
                remoteCoverUrl = book.coverUrl,
                title = book.title,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(0.71f)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (book.lastReadPage > 0) "CURRENTLY READING" else "ON DEVICE • WEBP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Page $currentDisplay of $pageTotal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBytes(book.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onOpenBook,
                    modifier = Modifier
                        .testTag("featured_open_button")
                        .minimumInteractiveComponentSize(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (book.lastReadPage > 0) {
                            stringResource(R.string.action_continue_reading)
                        } else {
                            stringResource(R.string.action_read)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BookshelfGridCard(
    book: BookEntity,
    transfer: TransferProgress?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = book.parsedStatus()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("book_card_${book.id}")
            .clickable(enabled = book.isReady, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.74f)
            ) {
                BookCoverArt(
                    localCoverPath = book.localCoverPath,
                    remoteCoverUrl = book.coverUrl,
                    title = book.title,
                    modifier = Modifier.fillMaxSize()
                )

                // Top-right badge
                val badgeBg = if (book.isReady) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                }
                val badgeFg = if (book.isReady) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (book.isReady) Icons.Default.CloudDone else Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = badgeFg,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (book.isReady) "${book.pageCount} WEBP" else ".ZIP",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeFg
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            val metaLine = buildString {
                append(book.category)
                if (book.fileSizeBytes > 0L) {
                    append(" • ")
                    append(formatBytes(book.fileSizeBytes))
                }
            }
            Text(
                text = metaLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            when {
                transfer != null || status == BookStatus.DOWNLOADING || status == BookStatus.EXTRACTING -> {
                    val progressVal = transfer?.progress
                    if (progressVal != null) {
                        LinearProgressIndicator(
                            progress = { progressVal },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = transfer?.detailLabel ?: "Preparing archive...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                book.isReady -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onOpen,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_book_button_${book.id}")
                                .minimumInteractiveComponentSize(),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = if (book.lastReadPage > 0) "p.${book.lastReadPage + 1}" else "Read",
                                maxLines = 1
                            )
                        }

                        IconButton(
                            onClick = onRequestDelete,
                            modifier = Modifier
                                .testTag("delete_book_button_${book.id}")
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                status == BookStatus.ERROR -> {
                    if (!book.errorMessage.isNullOrBlank()) {
                        Text(
                            text = book.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("retry_book_button_${book.id}")
                            .minimumInteractiveComponentSize(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                }

                else -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("download_book_button_${book.id}")
                            .minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_download))
                    }
                }
            }
        }
    }
}

@Composable
fun BookCoverArt(
    localCoverPath: String?,
    remoteCoverUrl: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    val coverModel: Any? = when {
        !localCoverPath.isNullOrBlank() && File(localCoverPath).exists() -> File(localCoverPath)
        !remoteCoverUrl.isNullOrBlank() -> remoteCoverUrl
        else -> null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF283146),
                        Color(0xFF1A202E)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)
            )
    ) {
        if (coverModel != null) {
            AsyncImage(
                model = coverModel,
                contentDescription = stringResource(R.string.cd_book_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFF3E7CE),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Tactile left book spine groove
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(10.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.14f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
fun CatalogSyncDialog(
    initialUrl: String,
    isSyncing: Boolean,
    onDismiss: () -> Unit,
    onConfirmSync: (String) -> Unit
) {
    var urlText by remember(initialUrl) { mutableStateOf(initialUrl) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
        title = {
            Text(
                text = "Remote Catalog Source",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter the URL of a remote catalog.json index. BS Reader fetches the catalog, downloads book .zip archives of pre-rasterized WebP pages, and extracts them locally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("catalog.json URL") },
                    placeholder = { Text("https://mrlrajin.github.io/bs-reader-manuals/catalog.json") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (urlText.isNotBlank()) onConfirmSync(urlText) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("catalog_url_input")
                )

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "EXPECTED CATALOG.JSON SCHEMA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = """
                                {
                                  "catalogTitle": "Press",
                                  "books": [{
                                    "id": "vol-01",
                                    "title": "Folio Title",
                                    "author": "Author",
                                    "zipUrl": "https://.../vol01.zip",
                                    "coverUrl": "https://.../cover.webp",
                                    "pageCount": 24
                                  }]
                                }
                            """.trimIndent(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSync(urlText) },
                enabled = urlText.isNotBlank() && !isSyncing,
                modifier = Modifier.testTag("fetch_catalog_confirm_button")
            ) {
                Text(if (isSyncing) "Syncing..." else "Fetch Catalog")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun AppCreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.bs_reader_logo),
                    contentDescription = stringResource(R.string.app_name),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .size(64.dp)
                        .padding(10.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.bookshelf_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "APP CREDIT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.app_credit),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_credits_button")
            ) {
                Text("Close")
            }
        }
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb >= 1024.0) {
        String.format("%.1f MB", kb / 1024.0)
    } else {
        String.format("%.0f KB", kb)
    }
}

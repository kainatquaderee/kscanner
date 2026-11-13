package com.kscanner.ui.document

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kscanner.core.imaging.ScanFilter
import com.kscanner.data.Page
import com.kscanner.ui.DocumentViewModel
import com.kscanner.ui.components.EmptyState
import com.kscanner.ui.components.LoadingOverlay
import com.kscanner.ui.components.Thumbnail
import com.kscanner.ui.components.formatBytes
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    documentId: Long,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onDeleted: () -> Unit,
) {
    LaunchedEffect(documentId) { viewModel.load(documentId) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var arrangeOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var actionPage by remember { mutableStateOf<Page?>(null) }

    LaunchedEffect(viewModel.error) {
        val message = viewModel.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    val document = viewModel.document
    val pages = viewModel.pages

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document?.name ?: "Document",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (document != null) {
                            Text(
                                text = "${document.pageCount} page${if (document.pageCount == 1) "" else "s"} · ${formatBytes(document.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = {
                                    menuOpen = false
                                    renameText = document?.name.orEmpty()
                                    renameOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Arrange pages") },
                                leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                                enabled = pages.size > 1,
                                onClick = { menuOpen = false; arrangeOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Share all pages") },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                enabled = pages.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(viewModel.shareIntentForPages(), "Share pages"),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy text") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                enabled = viewModel.hasText,
                                onClick = {
                                    menuOpen = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "KScanner text",
                                            viewModel.combinedText,
                                        ),
                                    )
                                    android.widget.Toast
                                        .makeText(context, "Text copied", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export text (.txt)") },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                enabled = viewModel.hasText,
                                onClick = {
                                    menuOpen = false
                                    viewModel.exportText { file ->
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(shareText(context, file), "Share text"),
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete document") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menuOpen = false; deleteOpen = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onAddPage) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add page")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            viewModel.export { file ->
                                runCatching {
                                    context.startActivity(Intent.createChooser(sharePdf(context, file), "Share PDF"))
                                }
                            }
                        },
                        enabled = pages.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export PDF")
                    }
                }
            }
        },
    ) { padding ->
        LoadingOverlay(
            visible = viewModel.loading || viewModel.busy,
            message = viewModel.busyLabel,
            modifier = Modifier.padding(padding),
        ) {
            if (pages.isEmpty() && !viewModel.loading) {
                EmptyState(
                    icon = Icons.Outlined.Description,
                    title = "No pages yet",
                    message = "Add a page with the camera to start this document.",
                    actionLabel = "Add page",
                    onAction = onAddPage,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                        PageCard(
                            page = page,
                            index = index,
                            onClick = { onOpenPage(index) },
                            onLongClick = { actionPage = page },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(renameText)
                    renameOpen = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete this document?") },
            text = { Text("Every page and its original scan will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.deleteDocument(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
        )
    }

    if (arrangeOpen) {
        ArrangeDialog(
            pages = pages,
            onDismiss = { arrangeOpen = false },
            onConfirm = { ordered ->
                viewModel.reorder(ordered)
                arrangeOpen = false
            },
        )
    }

    actionPage?.let { page ->
        ModalBottomSheet(onDismissRequest = { actionPage = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Page ${page.index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Recognise text") },
                    supportingContent = {
                        Text(if (page.ocrText.isNullOrBlank()) "Extract searchable text from this page" else "View recognised text")
                    },
                    leadingContent = { Icon(Icons.Outlined.TextFields, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                        actionPage = null
                        viewModel.runOcr(page)
                    },
                )
                ListItem(
                    headlineContent = { Text("Rotate left") },
                    leadingContent = { Icon(Icons.Outlined.RotateLeft, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                        actionPage = null
                        viewModel.rotatePage(page, clockwise = false)
                    },
                )
                ListItem(
                    headlineContent = { Text("Rotate right") },
                    leadingContent = { Icon(Icons.Outlined.RotateRight, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                        actionPage = null
                        viewModel.rotatePage(page, clockwise = true)
                    },
                )
                ListItem(
                    headlineContent = { Text("Delete page") },
                    leadingContent = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                        actionPage = null
                        viewModel.deletePage(page) { onDeleted() }
                    },
                )
            }
        }
    }

    viewModel.ocrText?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::clearOcr,
            title = { Text("Text — ${viewModel.ocrCaption}") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    item {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    viewModel.clearOcr()
                }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching { context.startActivity(Intent.createChooser(intent, "Share text")) }
                    viewModel.clearOcr()
                }) { Text("Share") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCard(
    page: Page,
    index: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box {
                Thumbnail(
                    path = page.processedPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f),
                    maxEdge = 520,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = "${index + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (!page.ocrText.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                    ) {
                        Text(
                            "TXT",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ScanFilter.fromId(page.filterId).label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${page.width}×${page.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ArrangeDialog(
    pages: List<Page>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var order by remember(pages) { mutableStateOf(pages.map { it.id }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arrange pages") },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                items(order.size) { position ->
                    val page = pages.firstOrNull { it.id == order[position] } ?: return@items
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${position + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(26.dp),
                        )
                        Text(
                            text = "Page ${page.index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                if (position > 0) {
                                    order = order.toMutableList().apply { add(position - 1, removeAt(position)) }
                                }
                            },
                            enabled = position > 0,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, "Move up", Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                if (position < order.lastIndex) {
                                    order = order.toMutableList().apply { add(position + 1, removeAt(position)) }
                                }
                            },
                            enabled = position < order.lastIndex,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, "Move down", Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(order) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sharePdf(context: android.content.Context, file: File): Intent =
    shareFile(context, file, "application/pdf")

private fun shareText(context: android.content.Context, file: File): Intent =
    shareFile(context, file, "text/plain")

private fun shareFile(context: android.content.Context, file: File, mime: String): Intent {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
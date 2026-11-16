package com.kscanner.ui.viewer

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlin.math.abs
import com.kscanner.data.Page
import com.kscanner.ui.DocumentViewModel
import com.kscanner.ui.components.rememberFileImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: DocumentViewModel,
    documentId: Long,
    startPage: Int,
    onBack: () -> Unit,
    onEditPage: (Page) -> Unit,
) {
    LaunchedEffect(documentId) { viewModel.load(documentId) }

    val pages = viewModel.pages
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showText by remember { mutableStateOf(false) }

    // The page last looked at, not the one the route was opened with: coming back from the editor
    // has to land on the page that was edited, which may be many swipes from the start page the
    // caller navigated with. Captured once, because the pager is built while the page list is
    // still empty and the effect below records whatever page it is showing — including the
    // placeholder 0 — so re-reading it afterwards would lose what was asked for.
    val landingPage = remember { (viewModel.viewedPage ?: startPage).coerceAtLeast(0) }

    val pagerState = rememberPagerState(
        initialPage = landingPage,
        pageCount = { pages.size },
    )

    // An initial page is not enough on its own: with no pages to measure it can settle on 0 and
    // stay there once they arrive. Jump once, when they do, and never again — a later change in
    // count must not drag the user back off the page they are reading.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(pages.size) {
        if (landed || pages.isEmpty()) return@LaunchedEffect
        landed = true
        val target = landingPage.coerceIn(0, pages.lastIndex)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }

    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (pages.isNotEmpty()) viewModel.rememberViewedPage(pagerState.currentPage)
    }

    LaunchedEffect(viewModel.error) {
        val message = viewModel.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearError()
    }

    val currentPage: Page? = pages.getOrNull(pagerState.currentPage)

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pages.isEmpty()) "" else "${pagerState.currentPage + 1} of ${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                },
                actions = {
                    // First action in the row, and reading the pager rather than the route: it
                    // edits the page that is on screen at the moment it is tapped, exactly as
                    // the actions beside it do.
                    IconButton(
                        onClick = {
                            val page = currentPage ?: return@IconButton
                            onEditPage(page)
                        },
                    ) {
                        Icon(Icons.Outlined.Edit, "Edit page", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val page = currentPage ?: return@IconButton
                            if (!page.ocrText.isNullOrBlank()) {
                                showText = !showText
                            } else {
                                viewModel.runOcr(page)
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.TextFields, "Recognise text", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val page = currentPage ?: return@IconButton
                            viewModel.rotatePage(page, clockwise = true)
                        },
                    ) {
                        Icon(Icons.Outlined.RotateRight, "Rotate", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val page = currentPage ?: return@IconButton
                            viewModel.turnOver(page)
                        },
                    ) {
                        Icon(Icons.Outlined.Flip, "Turn this page over", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val page = currentPage ?: return@IconButton
                            val file = File(page.processedPath)
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(intent, "Share page"))
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Share, "Share page", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                    val page = pages[index]
                    ZoomablePage(path = page.processedPath, revision = page.renderRevision)
                }
            }

            if (viewModel.busy) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(viewModel.busyLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (showText) {
                val page = currentPage
                if (page != null && !page.ocrText.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Recognised text", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { showText = false }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.Close, "Close", Modifier.size(18.dp))
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = page.ocrText.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How far a page may be magnified by pinching. */
private const val MAX_ZOOM = 5f

/**
 * A page that can be pinched and panned. Zoom resets when the gesture ends below the
 * natural size, which keeps pages from drifting off-screen.
 */
@Composable
private fun ZoomablePage(path: String, revision: Any?) {
    val image = rememberFileImage(path, 1800, revision)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Hand-written rather than detectTransformGestures because that detector consumes
                // every drag, which starves the pager underneath it: pages could not be swiped at
                // all. A single finger at natural size is a page turn, so it is left unconsumed;
                // a pinch, or a pan while zoomed in, belongs to the page and is consumed.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastSlop = false
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!pastSlop) {
                            pastSlop = pan.getDistance() > viewConfiguration.touchSlop ||
                                abs(1f - zoom) > 0.01f
                        }
                        val mine = event.changes.count { it.pressed } > 1 || scale > 1.01f
                        if (pastSlop && mine) {
                            val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            scale = next
                            if (next > 1.01f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Scanned page",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        } else {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
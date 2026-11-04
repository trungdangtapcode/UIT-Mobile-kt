package vn.edu.uit.mobile.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import vn.edu.uit.mobile.core.designsystem.R
import vn.edu.uit.mobile.domain.*

@Composable fun SemanticArticleBlock(block: ForumBlock, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray, source: String, onBrowser: (String) -> Unit, canLoadImage: (String) -> Boolean = { ForumUrls.image(it) != null }, pollContent: (@Composable (ForumPoll) -> Unit)? = null) {
    @Composable fun children() {
        block.children.forEach { SemanticArticleBlock(it, onLink, imageLoader, source, onBrowser, canLoadImage, pollContent) }
    }
    when (block.kind) {
        ForumBlockKind.POLL -> block.poll?.let { poll ->
            if (pollContent != null) {
                pollContent(poll)
            } else {
                TextButton(onClick = { onBrowser(source) }) { Text(stringResource(R.string.rich_unsupported)) }
            }
        }
        ForumBlockKind.CODE -> {
            val clipboard = LocalClipboardManager.current
            Surface(shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(block.label, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) { Text(stringResource(R.string.rich_copy_code)) }
                    }
                    Text(block.text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ForumBlockKind.QUOTE -> Surface(shape = TechnicalShapes.Control, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (block.label.isNotBlank()) Text(block.label, style = MaterialTheme.typography.labelLarge)
                if (block.children.isNotEmpty()) children() else ArticleRichText(block, onLink)
                block.links.forEach { link -> TextButton(onClick = { onLink(link.url) }) { Text(stringResource(R.string.rich_quote_source)) } }
            }
        }
        ForumBlockKind.DETAILS, ForumBlockKind.SPOILER -> {
            var expanded by rememberSaveable(block) { mutableStateOf(false) }
            val title = if (block.kind == ForumBlockKind.SPOILER) stringResource(if (expanded) R.string.rich_hide_spoiler else R.string.rich_show_spoiler) else block.label.ifBlank { stringResource(R.string.rich_details) }
            TextButton(onClick = { expanded = !expanded }) { Text(title) }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (block.children.isNotEmpty()) children() else ArticleRichText(block, onLink)
                }
            }
        }
        ForumBlockKind.TABLE -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            if (block.cells.isNotEmpty()) {
                block.cells.forEach { row ->
                    Row {
                        row.forEach { cell -> Column(Modifier.width(200.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { cell.forEach { SemanticArticleBlock(it, onLink, imageLoader, source, onBrowser, canLoadImage, pollContent) } } }
                    }
                    HorizontalDivider()
                }
            } else {
                block.rows.forEach { row -> Row { row.forEach { Text(it, Modifier.width(200.dp).padding(8.dp)) } } }
            }
        }
        ForumBlockKind.IMAGE -> ArticleImage(block, imageLoader, onBrowser, canLoadImage)
        ForumBlockKind.OMITTED -> TextButton(onClick = { onBrowser(source) }) { Text(stringResource(R.string.rich_abbreviated)) }
        ForumBlockKind.UNSUPPORTED -> TextButton(onClick = { onBrowser(source) }) { Text(stringResource(R.string.rich_unsupported)) }
        else -> if (block.children.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (block.label.isNotBlank()) Text(block.label)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { children() }
            }
        } else {
            ArticleRichText(block, onLink)
        }
    }
}

@Composable private fun ArticleRichText(block: ForumBlock, onLink: (String) -> Unit) {
    var revealed by rememberSaveable(block) { mutableStateOf(false) }
    val palette = MaterialTheme.colorScheme
    val runs = block.inline.ifEmpty { listOf(ForumInline(block.text)) }
    val text = buildAnnotatedString {
        runs.forEach { run ->
            if (run.spoiler && !revealed) {
                append("•••")
                return@forEach
            }
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = when {
                    run.change < 0 -> palette.errorContainer
                    run.change > 0 -> palette.primaryContainer
                    run.code -> palette.surfaceContainerHighest
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                },
                color = when {
                    run.change < 0 -> palette.onErrorContainer
                    run.change > 0 -> palette.onPrimaryContainer
                    else -> androidx.compose.ui.graphics.Color.Unspecified
                },
                textDecoration = when {
                    run.strike && run.underline -> TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                    run.strike -> TextDecoration.LineThrough
                    run.underline -> TextDecoration.Underline
                    else -> null
                }
            )
            val link = run.link
            if (link != null) pushLink(LinkAnnotation.Clickable(link, TextLinkStyles(SpanStyle(color = palette.primary, textDecoration = TextDecoration.Underline))) { onLink(link) })
            withStyle(style) { append(run.text) }
            if (run.link != null) pop()
        }
    }
    if (text.isNotBlank()) {
        Text(
            text,
            style = if (block.kind == ForumBlockKind.HEADING) {
                when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }
            } else {
                MaterialTheme.typography.bodyLarge
            }
        )
    }
    if (runs.any { it.spoiler }) TextButton(onClick = { revealed = !revealed }) { Text(stringResource(if (revealed) R.string.rich_hide_spoiler else R.string.rich_show_spoiler)) }
    // Legacy manually constructed blocks retain their explicit link actions.
    if (block.inline.isEmpty()) block.links.forEach { TextButton(onClick = { onLink(it.url) }) { Text(it.label.ifBlank { it.url }) } }
}

@Composable private fun ArticleImage(block: ForumBlock, loader: suspend (String) -> ByteArray, onBrowser: (String) -> Unit, canLoadImage: (String) -> Boolean) {
    val url = block.links.firstOrNull()?.url ?: return
    var request by remember(url) { mutableIntStateOf(0) }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(false) }
    var full by rememberSaveable(url) { mutableStateOf(false) }
    val supported = canLoadImage(url)
    LaunchedEffect(url, request) {
        if (request > 0 && supported) {
            loading = true
            failed = false
            try {
                bitmap = decodeArticleImage(loader(url))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    bitmap?.let { picture ->
        Image(picture, block.text, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
        TextButton(onClick = { full = true }, modifier = Modifier.testTag("forum-image-open")) { Text(stringResource(R.string.rich_fullscreen)) }
    }
    if (bitmap == null) {
        OutlinedButton(onClick = { if (supported) request++ else onBrowser(url) }, enabled = !loading, shape = TechnicalShapes.Control) {
            Text(
                stringResource(
                    if (!supported) {
                        R.string.rich_external_image
                    } else if (failed) {
                        R.string.rich_retry
                    } else {
                        R.string.rich_image
                    }
                )
            )
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("forum-image-loading"))
    if (failed) Text(stringResource(R.string.rich_image_failed), style = MaterialTheme.typography.bodySmall)
    if (block.text.isNotBlank()) Text(block.text, style = MaterialTheme.typography.labelSmall)
    if (full) ArticleImageDialog(block, bitmap, loader, onBrowser, canLoadImage) { full = false }
}

private suspend fun decodeArticleImage(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.IO) {
    require(bytes.size <= 3 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 8000000)
    val options = BitmapFactory.Options().apply { inSampleSize = if (maxOf(bounds.outWidth, bounds.outHeight) > 2048) 2 else 1 }
    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)).asImageBitmap()
}

@Composable private fun ArticleImageDialog(block: ForumBlock, preview: ImageBitmap?, loader: suspend (String) -> ByteArray, onBrowser: (String) -> Unit, canLoadImage: (String) -> Boolean, dismiss: () -> Unit) {
    val original = block.original ?: block.links.first().url
    var picture by remember(original) { mutableStateOf(preview) }
    var attempt by remember(original) { mutableIntStateOf(0) }
    var loaded by remember(original) { mutableStateOf(original == block.links.first().url && preview != null) }
    var failed by remember(original) { mutableStateOf(false) }
    var loading by remember(original) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun transform(zoom: Float, delta: Offset) {
        scale = (scale * zoom).coerceIn(1f, 6f)
        val image = picture
        val fit = if (image == null) 0f else minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val x = (((image?.width ?: 0) * fit * scale - size.width) / 2).coerceAtLeast(0f)
        val y = (((image?.height ?: 0) * fit * scale - size.height) / 2).coerceAtLeast(0f)
        pan = Offset((pan.x + delta.x).coerceIn(-x, x), (pan.y + delta.y).coerceIn(-y, y))
    }
    LaunchedEffect(original, attempt) {
        if (!loaded && canLoadImage(original)) {
            loading = true
            failed = false
            try {
                picture = decodeArticleImage(loader(original))
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.rich_close_image)) }
                    TextButton(onClick = { onBrowser(original) }) { Text(stringResource(R.string.rich_original)) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("forum-image-loading"))
                if (failed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.rich_image_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.rich_retry)) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("forum-image-viewer").clipToBounds().onSizeChanged { size = it }.pointerInput(Unit) { detectTransformGestures { _, delta, zoom, _ -> transform(zoom, delta) } }, contentAlignment = Alignment.Center) {
                    picture?.let {
                        Image(
                            it,
                            block.text,
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pan.x
                                translationY = pan.y
                            },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Text(block.text, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { transform(1 / 1.5f, Offset.Zero) }, enabled = scale > 1) { Text(stringResource(R.string.rich_zoom_out)) }
                    TextButton(onClick = {
                        scale = 1f
                        pan = Offset.Zero
                    }) { Text(stringResource(R.string.rich_reset_zoom)) }
                    TextButton(onClick = { transform(1.5f, Offset.Zero) }, enabled = scale < 6) { Text(stringResource(R.string.rich_zoom_in)) }
                }
            }
        }
    }
}

/** Reuse the native semantic renderer for already-sanitized public university articles. */
@Composable
fun PublicArticleBlock(block: ForumBlock, source: String, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray) {
    SemanticArticleBlock(block, onLink, imageLoader, source, onLink, canLoadImage = AnnouncementUrls::publicAsset)
}

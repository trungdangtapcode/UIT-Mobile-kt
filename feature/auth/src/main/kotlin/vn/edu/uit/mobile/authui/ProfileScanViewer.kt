package vn.edu.uit.mobile.authui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.uit.mobile.common.AppError
import vn.edu.uit.mobile.designsystem.NativePdfDocument
import vn.edu.uit.mobile.designsystem.TechnicalShapes
import vn.edu.uit.mobile.feature.auth.R

@Composable fun ProfileScanViewer(scan: ProfileScanState, onClose: () -> Unit, onRetry: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ProfileScanContent(scan, onClose, onRetry)
    }
}

private data class DecodedScan(val image: ImageBitmap?, val pageCount: Int = 1)

internal fun isPdfScan(bytes: ByteArray): Boolean = bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-"

internal fun decodePdfScanPage(bytes: ByteArray, pageIndex: Int, cacheDirectory: File): Pair<ImageBitmap, Int>? = runCatching {
    NativePdfDocument(bytes, cacheDirectory).use { document ->
        document.render(pageIndex.coerceIn(0, document.pageCount - 1) + 1).asImageBitmap() to document.pageCount
    }
}.getOrNull()

@Composable fun ProfileScanContent(scan: ProfileScanState, onClose: () -> Unit = {}, onRetry: () -> Unit = {}) {
    val cacheDirectory = LocalContext.current.cacheDir
    var pageIndex by remember(scan.bytes) { mutableIntStateOf(0) }
    val decoded by produceState<DecodedScan?>(null, scan.bytes, pageIndex) {
        value = null
        val bytes = scan.bytes ?: return@produceState
        value = withContext(Dispatchers.Default) {
            if (isPdfScan(bytes)) {
                val rendered = decodePdfScanPage(bytes, pageIndex, cacheDirectory)
                DecodedScan(rendered?.first, rendered?.second ?: 1)
            } else {
                DecodedScan(
                    runCatching {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 200_000_000) return@runCatching null
                        options.inSampleSize = 1
                        while (options.outWidth / options.inSampleSize > 4096 || options.outHeight / options.inSampleSize > 4096) options.inSampleSize *= 2
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    }.getOrNull()
                )
            }
        }
    }
    val image = decoded?.image
    val pageCount = decoded?.pageCount ?: 1
    Surface(Modifier.fillMaxSize().testTag("profile-scan-viewer"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.scan_close)) }
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(scan.document.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.scan_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                scan.loading || (scan.bytes != null && decoded == null) -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.scan_opening), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                image != null -> ScanImage(
                    image = image,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    pageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                    pageCount = pageCount,
                    onPageChange = { pageIndex = it.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }
                )
                else -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Outlined.BrokenImage, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(
                                when (scan.error) {
                                    AppError.SessionExpired -> R.string.scan_err_signin
                                    AppError.NetworkOffline -> R.string.scan_err_offline
                                    else -> R.string.scan_err_generic
                                }
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedButton(onClick = onRetry, shape = TechnicalShapes.Field) { Text(stringResource(R.string.profile_try_again)) }
                    }
                }
            }
        }
    }
}

@Composable private fun ScanImage(
    image: ImageBitmap,
    modifier: Modifier,
    pageIndex: Int = 0,
    pageCount: Int = 1,
    onPageChange: (Int) -> Unit = {}
) {
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val fit = if (width > 0 && height > 0) min(width / image.width, height / image.height) else 0f
    val fittedWidth = image.width * fit
    val fittedHeight = image.height * fit
    fun changeZoom(next: Float, centroid: Offset = Offset(width / 2, height / 2), pan: Offset = Offset.Zero) {
        val value = next.coerceIn(1f, 6f)
        val ratio = value / zoom
        val center = centroid - Offset(width / 2, height / 2)
        val proposed = offset * ratio + center * (1 - ratio) + pan
        val limitX = ((fittedWidth * value - width) / 2).coerceAtLeast(0f)
        val limitY = ((fittedHeight * value - height) / 2).coerceAtLeast(0f)
        offset = Offset(proposed.x.coerceIn(-limitX, limitX), proposed.y.coerceIn(-limitY, limitY))
        zoom = value
    }
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLow).clipToBounds().onSizeChanged { viewport = it }
                .testTag("profile-scan-image").semantics { stateDescription = "${(zoom * 100).toInt()} percent zoom" }
                .pointerInput(image, viewport) { detectTransformGestures { centroid, pan, scale, _ -> changeZoom(zoom * scale, centroid, pan) } }
                .pointerInput(image, viewport) { detectTapGestures(onDoubleTap = { changeZoom(if (zoom > 1f) 1f else 3f, it) }) },
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            Image(
                image,
                stringResource(R.string.scan_image_desc),
                Modifier.size(with(density) { fittedWidth.toDp() }, with(density) { fittedHeight.toDp() }).graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                }
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { onPageChange(pageIndex - 1) }, enabled = pageIndex > 0) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, stringResource(R.string.scan_prev_page))
                }
                Text(stringResource(R.string.scan_page_indicator, pageIndex + 1, pageCount), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { onPageChange(pageIndex + 1) }, enabled = pageIndex < pageCount - 1) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.scan_next_page))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { changeZoom(zoom - 0.5f) }, enabled = zoom > 1f) { Icon(Icons.Outlined.ZoomOut, stringResource(R.string.scan_zoom_out)) }
            Text("${(zoom * 100).toInt()}%", Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { changeZoom(zoom + 0.5f) }, enabled = zoom < 6f) { Icon(Icons.Outlined.ZoomIn, stringResource(R.string.scan_zoom_in)) }
            TextButton(onClick = { changeZoom(1f) }, shape = TechnicalShapes.Control) { Text(stringResource(R.string.scan_fit)) }
        }
        Text(stringResource(R.string.scan_hint), Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

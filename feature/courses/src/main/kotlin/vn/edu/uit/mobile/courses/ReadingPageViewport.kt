package vn.edu.uit.mobile.courses

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlin.math.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.courses.R

/** Fit the original rectangular page; normalized pan/anchors survive different phone sizes. */
@Composable internal fun ReadingPageViewport(
    image: Bitmap,
    pageCount: Int,
    position: ReadingPosition,
    notes: List<ReadingNote>,
    placing: Boolean,
    enabled: Boolean,
    fitWidthRequest: Int,
    onFitWidthApplied: () -> Unit,
    onPosition: (ReadingPosition) -> Unit,
    onPin: (Float, Float) -> Unit,
    onNote: (ReadingNote) -> Unit,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var local by remember(image, position.page) { mutableStateOf(position) }
    LaunchedEffect(position) { local = position }
    val density = LocalDensity.current
    val fit = if (size.width > 0 && size.height > 0) min(size.width.toFloat() / image.width, size.height.toFloat() / image.height) else 1f
    val width = image.width * fit
    val height = image.height * fit
    fun limits(zoom: Float) = Offset(max(0f, (width * zoom - size.width) / 2), max(0f, (height * zoom - size.height) / 2))
    fun update(zoom: Float, offset: Offset) {
        val z = zoom.coerceIn(1f, 4f)
        val max = limits(z)
        local = local.copy(zoom = z, panX = if (max.x > 0) (offset.x / max.x).coerceIn(-1f, 1f) else 0f, panY = if (max.y > 0) (offset.y / max.y).coerceIn(-1f, 1f) else 0f)
        onPosition(local)
    }
    LaunchedEffect(fitWidthRequest, size) {
        if (fitWidthRequest > 0 && size.width > 0) {
            val zoom = (size.width / width).coerceIn(1f, 4f)
            update(zoom, Offset(0f, limits(zoom).y))
            onFitWidthApplied()
        }
    }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val bounds = limits(local.zoom)
        update(local.zoom * zoom, Offset(local.panX * bounds.x, local.panY * bounds.y) + pan)
    }
    val maxPan = limits(local.zoom)
    val offset = Offset(local.panX * maxPan.x, local.panY * maxPan.y)
    val description = stringResource(R.string.rd_canvas_desc, position.page, pageCount)
    Box(
        modifier.fillMaxWidth().onSizeChanged { size = it }.clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("reading-page")
            .transformable(transform, canPan = { local.zoom > 1f }, enabled = enabled && !placing)
            .pointerInput(image, enabled, placing, size) {
                detectTapGestures(
                    onDoubleTap = if (placing) {
                        null
                    } else {
                        (
                            { point ->
                                if (enabled) {
                                    val zoom = if (local.zoom > 1f) 1f else 2f
                                    val ratio = zoom / local.zoom
                                    val bounds = limits(local.zoom)
                                    val focus = point - Offset(size.width / 2f, size.height / 2f)
                                    update(zoom, Offset(local.panX * bounds.x, local.panY * bounds.y) * ratio + focus * (1 - ratio))
                                }
                            }
                            )
                    },
                    onTap = { point ->
                        if (enabled && placing && size.width > 0) {
                            val bounds = limits(local.zoom)
                            val left = (size.width - width * local.zoom) / 2 + local.panX * bounds.x
                            val top = (size.height - height * local.zoom) / 2 + local.panY * bounds.y
                            val x = (point.x - left) / (width * local.zoom)
                            val y = (point.y - top) / (height * local.zoom)
                            if (x in 0f..1f && y in 0f..1f) onPin(x, y)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            image.asImageBitmap(),
            description,
            Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() }).graphicsLayer {
                scaleX = local.zoom
                scaleY = local.zoom
                translationX = offset.x
                translationY = offset.y
            }
        )
        notes.forEachIndexed { index, note ->
            val x = (size.width - width * local.zoom) / 2 + offset.x + note.x * width * local.zoom
            val y = (size.height - height * local.zoom) / 2 + offset.y + note.y * height * local.zoom
            if (x in 0f..size.width.toFloat() && y in 0f..size.height.toFloat()) {
                val target = with(density) { 48.dp.toPx() }
                val pinDescription = stringResource(R.string.rd_pin_desc, index + 1, position.page)
                Box(Modifier.align(Alignment.TopStart).offset { IntOffset((x - target / 2).roundToInt(), (y - target / 2).roundToInt()) }.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton({ onNote(note) }, enabled = enabled && !placing, modifier = Modifier.size(48.dp).testTag("reading-pin-${note.id}").semantics { contentDescription = pinDescription }) {
                        Surface(Modifier.size(24.dp), shape = TechnicalShapes.Tile, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Text((index + 1).toString(), fontFamily = CampusMono, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics {}) }
                        }
                    }
                }
            }
        }
    }
}

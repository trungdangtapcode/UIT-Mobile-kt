package vn.edu.uit.mobile.schedule

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.core.designsystem.R as DesignR
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.domain.*
import vn.edu.uit.mobile.feature.schedule.R
import vn.edu.uit.mobile.model.ScheduleKind

/** A bounded, one-column phone image. Only the redacted plan reaches the drawing code. */
object TimetableImageRenderer {
    fun render(context: Context, plan: TimetableImagePlan, fontScale: Float = context.resources.configuration.fontScale): Bitmap {
        require(fontScale.isFinite())
        val scale = fontScale.coerceIn(1f, 2f)
        val colors = uitColorScheme(context, dark = true, dynamic = false)
        val regular = context.resources.getFont(DesignR.font.ibm_plex_sans_regular)
        val medium = context.resources.getFont(DesignR.font.ibm_plex_sans_semibold)
        val commands = mutableListOf<(Canvas) -> Unit>()
        var y = 24f
        fun text(value: String, size: Float, color: Int, strong: Boolean = false, x: Float = 24f, width: Int = 312): Float {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size * scale
                typeface = if (strong) medium else regular
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
            val top = y
            commands += { canvas ->
                canvas.save()
                canvas.translate(x, top)
                layout.draw(canvas)
                canvas.restore()
            }
            return layout.height.toFloat()
        }
        val ink = colors.onSurface.toArgb()
        val muted = colors.onSurfaceVariant.toArgb()
        val blue = colors.primary.toArgb()
        val emblem = requireNotNull(context.getDrawable(DesignR.drawable.uit_emblem)).mutate()
        commands += { canvas ->
            emblem.setBounds(24, 22, 56, 54)
            emblem.draw(canvas)
        }
        y += text(context.getString(DesignR.string.brand_name), 15f, blue, true, 68f, 268)
        y = maxOf(y, 58f) + 12f
        y += text(context.getString(R.string.ti_image_title), 26f, ink, true) + 8f
        val range = if (plan.options.from == plan.options.through) plan.options.from.toString() else "${plan.options.from} — ${plan.options.through}"
        y += text("$range · GMT+07", 12f, blue) + 8f
        if (plan.identity.isNotBlank()) y += text(plan.identity, 13f, ink, true) + 8f
        y += text(context.getString(R.string.ti_image_scope), 11f, muted) + 20f
        for (day in plan.days) {
            y += text(day.date.toString(), 16f, blue, true) + 10f
            if (day.rows.isEmpty()) {
                y += text(context.getString(R.string.ti_image_empty), 12f, muted) + 20f
                continue
            }
            for (row in day.rows) {
                val top = y
                val index = commands.size
                y += 16f
                val label = context.getString(
                    when (row.kind) {
                        ScheduleKind.CANCELED -> R.string.ti_canceled
                        ScheduleKind.MAKE_UP -> R.string.ti_makeup
                        else -> R.string.ti_class
                    }
                )
                val accent = if (row.kind == ScheduleKind.CANCELED) colors.error.toArgb() else blue
                y += text(label + " · " + clockText(row.start) + if (row.end > row.start) "–${clockText(row.end)}" else "", 12f, accent, true, 32f, 296) + 8f
                if (row.title.isNotBlank()) y += text(row.title, 16f, ink, true, 32f, 296) + 6f
                if (row.classCode.isNotBlank()) y += text(row.classCode, 11f, muted, x = 32f, width = 296) + 4f
                if (row.room.isNotBlank()) y += text(context.getString(R.string.ti_image_room, row.room), 12f, muted, x = 32f, width = 296) + 4f
                val bottom = y + 16f
                commands.add(index) { canvas ->
                    val path = frame(16f, top, 344f, bottom)
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.surface.toArgb() })
                    canvas.drawPath(
                        path,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = colors.outlineVariant.toArgb()
                            style = Paint.Style.STROKE
                            strokeWidth = 0.8f
                        }
                    )
                }
                y = bottom + 12f
            }
            y += 8f
        }
        y += text(context.getString(R.string.ti_image_saved, dateText(plan.updatedAt), clockText(plan.updatedAt)), 10f, muted) + 6f
        y += text(context.getString(R.string.ti_image_footer), 10f, muted) + 24f
        require(y <= 2600f) { "Image too tall" }
        val bitmap = Bitmap.createBitmap(1080, kotlin.math.ceil(y * 3).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(colors.background.toArgb())
            canvas.scale(3f, 3f)
            commands.forEach { it(canvas) }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
    private fun frame(left: Float, top: Float, right: Float, bottom: Float) = Path().apply {
        moveTo(left + 8, top)
        lineTo(right - 8, top)
        lineTo(right, top + 8)
        lineTo(right, bottom - 28)
        lineTo(right - 28, bottom)
        lineTo(left + 8, bottom)
        lineTo(left, bottom - 8)
        lineTo(left, top + 8)
        close()
    }
}

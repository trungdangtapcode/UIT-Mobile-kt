package vn.edu.uit.mobile.auth

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import vn.edu.uit.mobile.core.auth.R

/** Native controls remain usable when the remote page cannot render. */
internal class SsoBrowserDialog(activity: Activity) {
    val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
    val web = WebView(activity)
    private val density = activity.resources.displayMetrics.density
    private val status = TextView(activity).apply {
        id = R.id.sso_status
        setTextColor(Color.rgb(30, 50, 70))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Color.rgb(0, 90, 160))
    }
    private val retry = Button(activity).apply {
        id = R.id.sso_retry
        setText(R.string.sso_retry)
        visibility = View.GONE
    }
    private val skipDaa = Button(activity).apply {
        id = R.id.sso_continue_moodle
        setText(R.string.sso_continue_moodle)
        visibility = View.GONE
    }
    private val close = Button(activity).apply {
        id = R.id.sso_close
        setText(R.string.sso_close)
    }

    init {
        val heading = TextView(activity).apply {
            setText(R.string.sso_title)
            textSize = 18f
            setTextColor(Color.rgb(0, 75, 130))
            setPadding(dp(16), 0, 0, 0)
        }
        val toolbar = LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, dp(56), 1f).apply { heading.gravity = android.view.Gravity.CENTER_VERTICAL })
            addView(close, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(skipDaa, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        dialog.setContentView(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun show(onClose: () -> Unit, onRetry: () -> Unit, onContinue: () -> Unit) {
        close.setOnClickListener { onClose() }
        retry.setOnClickListener { onRetry() }
        skipDaa.setOnClickListener { onContinue() }
        dialog.setOnCancelListener { onClose() }
        dialog.setOnDismissListener { onClose() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    fun loading(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(30, 50, 70))
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    fun ready(message: String) {
        status.text = message
        progress.visibility = View.INVISIBLE
        retry.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    fun error(message: String, canRetry: Boolean = true) {
        status.text = message
        status.setTextColor(Color.rgb(155, 30, 30))
        progress.visibility = View.INVISIBLE
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        web.visibility = View.GONE
    }

    fun allowMoodleOnly() {
        skipDaa.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

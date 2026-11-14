package com.github.vermilion10.pancar.stream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

// Floating live pill over the game; it is captured in the stream like everything else on screen.
class LiveBubble(
    private val context: Context,
    private val hasMic: Boolean,
    private val onToggleMic: () -> Unit,
    private val onTogglePrivacy: () -> Unit,
    private val onStop: () -> Unit,
) {
    companion object {
        private const val CHAT_LINES = 3
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val liveRed = Color.parseColor("#E8452C")
    private val idleGrey = Color.parseColor("#8A8494")
    private val privacyAmber = Color.parseColor("#F2B84B")

    private fun dp(v: Int) = (v * density).toInt()

    private val dotShape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(idleGrey) }
    private val dot = View(context).apply { background = dotShape }
    private val label = boldText()
    private val micButton = action("Mute") { onToggleMic(); refresh() }
    private val privacyButton = action("Hide") { onTogglePrivacy(); refresh() }
    private val stopButton = action("Stop") { onStop() }
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        if (hasMic) addView(micButton)
        addView(privacyButton)
        addView(stopButton)
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        addView(label)
        addView(actions)
    }
    private val chatView = TextView(context).apply {
        setTextColor(Color.parseColor("#E8E1E9"))
        textSize = 12f
        maxWidth = dp(280)
        maxLines = CHAT_LINES * 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
        setPadding(0, dp(6), 0, 0)
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#CC121016"))
        }
        addView(pill)
        addView(chatView)
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun boldText() = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun action(text: String, onClick: () -> Unit) = boldText().apply {
        this.text = text
        setPadding(dp(12), dp(4), dp(4), dp(4))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }
        refresh()
        wm.addView(root, params)
        handler.post(tick)
    }

    fun dismiss() {
        handler.removeCallbacks(tick)
        runCatching { wm.removeView(root) }
    }

    private fun refresh() {
        val status = StreamController.status.value
        val privacy = StreamController.privacy.value
        val viewers = StreamController.viewers.value
        val (text, color) = when (status) {
            is StreamStatus.Live -> {
                val base = "LIVE  " + formatElapsed(SystemClock.elapsedRealtime() - status.since)
                val withViewers = if (viewers != null) "$base  ·  $viewers watching" else base
                val links = StreamController.links.value
                val up = links.count { it.state is LinkState.Live }
                val withLinks = if (up < links.size) "$withViewers  ·  $up/${links.size} up" else withViewers
                withLinks to if (privacy) privacyAmber else liveRed
            }
            is StreamStatus.Reconnecting -> "Reconnecting" to idleGrey
            else -> "Connecting" to idleGrey
        }
        label.text = if (privacy && status is StreamStatus.Live) "$text  ·  hidden" else text
        dotShape.setColor(color)
        micButton.text = if (StreamController.micMuted.value) "Unmute" else "Mute"
        privacyButton.text = if (privacy) "Show" else "Hide"

        val lines = StreamController.chat.value.takeLast(CHAT_LINES)
        chatView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        chatView.text = lines.joinToString("\n") { "${it.author}: ${it.text}" }
    }
}

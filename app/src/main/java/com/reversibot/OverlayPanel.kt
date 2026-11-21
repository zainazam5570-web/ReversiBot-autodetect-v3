package com.reversibot

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.reversibot.engine.Level
import com.reversibot.vision.Reading

/** Screen ke side par chhota floating panel: Start/Stop, strength, calibrate, mini board, status. */
class OverlayPanel(
    private val ctx: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun onAutoChanged(on: Boolean)
        fun onLevelChanged(level: Level)
        fun onCalibrate()
        fun onClose()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(ctx)
    private val body = LinearLayout(ctx)
    private val status = TextView(ctx)
    private val mini = MiniBoardView(ctx)
    private lateinit var autoBtn: TextView
    private lateinit var viewBtn: TextView
    private val levelBtns = LinkedHashMap<Level, TextView>()
    private var auto = false
    private var added = false
    private var lastText = ""

    private val lp = WindowManager.LayoutParams(
        dp(150), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun bg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = bg(color, 8)
            setOnClickListener { onClick() }
        }

    private fun lpMargin(top: Int = 4, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            if (weight > 0f) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, weight
        ).apply { topMargin = dp(top) }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.background = bg(0xE61B2530.toInt(), 12)

        // header (drag handle + collapse + close)
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(ctx).apply {
            text = "⠿ Reversi Bot"
            setTextColor(0xFFE8EEF4.toInt()); textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        header.addView(handle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "–"; textSize = 16f; setTextColor(0xFFE8EEF4.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        header.addView(TextView(ctx).apply {
            text = "✕"; textSize = 14f; setTextColor(0xFFEF4444.toInt())
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { cb.onClose() }
        })
        root.addView(header)
        attachDrag(handle)

        body.orientation = LinearLayout.VERTICAL
        root.addView(body)

        autoBtn = pill("▶ START", 0xFF16A34A.toInt()) { setAuto(!auto, notify = true) }
        body.addView(autoBtn, lpMargin(2))

        for (lv in Level.values()) {
            val b = pill(lv.label, 0xFF2C3E50.toInt()) { selectLevel(lv, notify = true) }
            levelBtns[lv] = b
            body.addView(b, lpMargin(4))
        }
        selectLevel(prefs.level, notify = false)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("📐 Manual", 0xFF1E40AF.toInt()) { cb.onCalibrate() }, lpMargin(0, 1f).apply { rightMargin = dp(3) })
        viewBtn = pill("👁 View", 0xFF2C3E50.toInt()) {
            prefs.showMini = !prefs.showMini
            applyMiniVisibility()
        }
        row.addView(viewBtn, lpMargin(0, 1f).apply { leftMargin = dp(3) })
        body.addView(row, lpMargin(6))

        body.addView(mini, LinearLayout.LayoutParams(dp(138), dp(138)).apply { topMargin = dp(6) })
        applyMiniVisibility()

        status.textSize = 11f
        status.setTextColor(0xFFB6C4D2.toInt())
        status.text = "Taiyar"
        status.maxLines = 5
        body.addView(status, lpMargin(6))
    }

    private fun applyMiniVisibility() {
        mini.visibility = if (prefs.showMini) View.VISIBLE else View.GONE
        viewBtn.background = bg(if (prefs.showMini) 0xFF1E40AF.toInt() else 0xFF2C3E50.toInt(), 8)
    }

    private fun selectLevel(lv: Level, notify: Boolean) {
        prefs.level = lv
        for ((k, v) in levelBtns) v.background = bg(if (k == lv) 0xFF2563EB.toInt() else 0xFF2C3E50.toInt(), 8)
        if (notify) cb.onLevelChanged(lv)
    }

    fun setAuto(on: Boolean, notify: Boolean) {
        auto = on
        ui.post {
            autoBtn.text = if (on) "⏸ STOP" else "▶ START"
            autoBtn.background = bg(if (on) 0xFFDC2626.toInt() else 0xFF16A34A.toInt(), 8)
        }
        if (notify) cb.onAutoChanged(on)
    }

    private fun attachDrag(handle: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    if (added) wm.updateViewLayout(root, lp)
                    true
                }
                MotionEvent.ACTION_UP -> { prefs.panelX = lp.x; prefs.panelY = lp.y; true }
                else -> false
            }
        }
    }

    fun show() {
        val dm = ctx.resources.displayMetrics
        lp.x = if (prefs.panelX != Int.MIN_VALUE) prefs.panelX else dm.widthPixels - dp(150) - dp(4)
        lp.y = if (prefs.panelY != Int.MIN_VALUE) prefs.panelY else dm.heightPixels / 6
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        added = true
    }

    fun remove() {
        if (added) {
            try { wm.removeView(root) } catch (e: Exception) { }
            added = false
        }
    }

    fun setVisible(v: Boolean) { ui.post { root.visibility = if (v) View.VISIBLE else View.GONE } }

    fun setStatus(text: String) {
        if (text == lastText) return
        lastText = text
        ui.post { status.text = text }
    }

    fun setFrame(r: Reading?, best: Int) {
        if (prefs.showMini) mini.set(r, best)
    }

    /** Panel ka screen rectangle (board ke upar aa jane ki warning ke liye). */
    fun screenRect(): Rect? {
        if (!added || root.visibility != View.VISIBLE || root.width == 0) return null
        return Rect(lp.x, lp.y, lp.x + root.width, lp.y + root.height)
    }
}

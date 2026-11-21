package com.reversibot

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: 3 permissions + Start. Baaqi sab kaam floating panel se hota hai. */
class MainActivity : Activity() {

    private val REQ_CAPTURE = 100
    private val REQ_NOTIF = 101

    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvRunning: TextView
    private lateinit var btnStart: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = 0xFF1F2937.toInt()) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(2)) }

    private fun button(s: String, onClick: () -> Unit) =
        Button(this).apply { text = s; setAllCaps(false); setOnClickListener { onClick() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
        }
        col.addView(text("♟ Reversi Bot", 26f, Color.BLACK))
        col.addView(text("Screen dekh kar khud khelta hai. Pehle ye 3 steps poore karein:", 14f, 0xFF4B5563.toInt()))

        col.addView(text("1) Display over other apps", 16f, Color.BLACK))
        tvOverlay = text("")
        col.addView(tvOverlay)
        col.addView(button("Overlay permission kholein") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        col.addView(text("2) Accessibility service (sirf tap karne ke liye)", 16f, Color.BLACK))
        tvAccess = text("")
        col.addView(tvAccess)
        col.addView(button("Accessibility settings kholein") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Downloaded Apps / Installed apps mein 'Reversi Bot Tapper' ko ON karein", Toast.LENGTH_LONG).show()
        })
        col.addView(text(
            "Agar ON nahi ho raha (grey): Settings > Apps > Reversi Bot > ⋮ (teen dots) > 'Allow restricted settings', phir dobara try karein.",
            12f, 0xFF6B7280.toInt()
        ))

        col.addView(text("3) Start", 16f, Color.BLACK))
        btnStart = button("▶ Bot shuru karein") { startBot() }
        col.addView(btnStart)
        tvRunning = text("")
        col.addView(tvRunning)
        col.addView(button("■ Bot band karein") {
            startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
        })

        col.addView(text(
            "Kaise chalayein:\n" +
                "• Start dabayen, screen share par 'Entire screen / Poori screen' chunein.\n" +
                "• Game kholein. Side par chhota panel aayega.\n" +
                "• Board khud pehchana jata hai (calibration ki zaroorat nahi). Agar na mile to 📐 Manual use karein.\n" +
                "• Strength chunein, ▶ START dabayen. Bot khud apna color aur baari pehchanta hai.\n" +
                "• Panel ko board ke upar na rakhein (side mein khiskayen).",
            13f, 0xFF374151.toInt()
        ))

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, BotAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val ac = accessibilityOn()
        tvOverlay.text = if (ov) "✅ ON" else "❌ OFF"
        tvAccess.text = if (ac) "✅ ON" else "❌ OFF"
        tvRunning.text = if (BotService.running) "🟢 Bot chal raha hai" else "⚪ Bot band hai"
        val ready = ov && ac
        btnStart.alpha = if (ready) 1f else 0.5f
    }

    private fun startBot() {
        refresh()
        if (!Settings.canDrawOverlays(this) || !accessibilityOn()) {
            Toast.makeText(this, "Pehle step 1 aur 2 poore karein", Toast.LENGTH_LONG).show()
            return
        }
        if (BotService.running) {
            Toast.makeText(this, "Bot pehle se chal raha hai", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, BotService::class.java)
                    .putExtra(BotService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(BotService.EXTRA_DATA, data)
                startForegroundService(i)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture ki ijazat nahi mili", Toast.LENGTH_LONG).show()
            }
        }
    }
}

package com.example.chessoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

private const val OVL = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val engine = Engine()
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var held: Image? = null
    private var sw = 0
    private var sh = 0
    private var fab: TextView? = null
    private var arrow: ArrowView? = null
    private var panel: View? = null
    private var started = false
    private val cb = object : MediaProjection.Callback() {
        override fun onStop() { ui.post { stopSelf() } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startFg()
        if (started) return START_NOT_STICKY
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        started = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("p", MODE_PRIVATE)
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(i.getIntExtra("code", 0), data)
        projection = p
        p.registerCallback(cb, ui)
        val rd = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = rd
        rd.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) synchronized(lock) { held?.close(); held = img }
        }, ui)
        vd = p.createVirtualDisplay(
            "cap", sw, sh, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )
        createArrow()
        createFab()
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ov", "Overlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ov")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess overlay работает").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- overlay windows ----------

    private fun createArrow() {
        val v = ArrowView(this)
        arrow = v
        applyBoard()
        val lp = WindowManager.LayoutParams(
            sw, sh, OVL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
    }

    private fun applyBoard() {
        arrow?.let {
            it.bx = prefs.getInt("bx", 0).toFloat()
            it.by = prefs.getInt("by", (sh - sw) / 2).toFloat()
            it.bs = prefs.getInt("bs", sw).toFloat()
            it.flip = prefs.getBoolean("flip", false)
            it.invalidate()
        }
    }

    private fun createFab() {
        val lp = WindowManager.LayoutParams(
            dp(56), dp(56), OVL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(200) }
        val b = TextView(this).apply {
            text = "♞"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC3F51B5.toInt()) }
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(v, lp) }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onFab()
                }
                return true
            }
        })
        fab = b
        wm.addView(b, lp)
    }

    // ---------- capture ----------

    private fun grab(): Bitmap? {
        synchronized(lock) {
            val img = held ?: return null
            val pl = img.planes[0]
            val rs = pl.rowStride
            val ps = pl.pixelStride
            pl.buffer.rewind()
            val full = Bitmap.createBitmap(rs / ps, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(pl.buffer)
            return Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
    }

    private fun onFab() {
        if (panel != null) { hidePanel(); return }
        arrow?.let { it.from = -1; it.to = -1 }
        fab?.visibility = View.INVISIBLE
        arrow?.visibility = View.INVISIBLE
        ui.postDelayed({
            bg.execute {
                val bmp = grab()
                bmp?.let {
                    try {
                        val f = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "last_screenshot.png")
                        f.outputStream().use { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o) }
                    } catch (e: Exception) { }
                }
                ui.post {
                    fab?.visibility = View.VISIBLE
                    arrow?.visibility = View.VISIBLE
                    showPanel(bmp != null)
                }
            }
        }, 300)
    }

    // ---------- panel ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 13f) =
        TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE) }

    private fun seek(title: String, key: String, minV: Int, maxV: Int, def: Int, onCh: () -> Unit): View {
        val label = tv("$title: ${prefs.getInt(key, def)}")
        val sb = SeekBar(this).apply {
            this.max = maxV
            this.min = minV
            progress = prefs.getInt(key, def)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    label.text = "$title: $p"
                    prefs.edit().putInt(key, p).apply()
                    onCh()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(sb) }
    }

    private fun showPanel(shot: Boolean) {
        if (panel != null) return
        arrow?.let { it.frame = true; it.invalidate() }
        val res = tv(
            if (shot) "Скриншот: Android/data/$packageName/files/Pictures/last_screenshot.png"
            else "Скриншот не получен. Введите FEN вручную.", 12f
        )
        val fen = EditText(this).apply {
            setText(prefs.getString("fen", START_FEN)); setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); hint = "FEN"; textSize = 12f; maxLines = 3
        }
        val flip = CheckBox(this).apply {
            text = "Чёрные снизу (перевернуть доску)"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("flip", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("flip", c).apply(); applyBoard() }
        }
        val bl = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@OverlayService).apply { text = "Анализ"; setOnClickListener { analyze(fen.text.toString(), res) } }, bl)
            addView(Button(this@OverlayService).apply { text = "Скрыть"; setOnClickListener { hidePanel() } }, bl)
            addView(Button(this@OverlayService).apply { text = "Выход"; setOnClickListener { stopSelf() } }, bl)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(tv("Chess overlay (FEN → лучший ход)", 15f))
            addView(fen)
            addView(flip)
            addView(seek("Сложность 1-10", "lvl", 1, 10, 5) {})
            addView(tv("Калибровка рамки доски (жёлтая сетка):", 12f))
            addView(seek("Доска X", "bx", 0, sw, 0) { applyBoard() })
            addView(seek("Доска Y", "by", 0, sh, (sh - sw) / 2) { applyBoard() })
            addView(seek("Размер", "bs", sw / 4, sw, sw) { applyBoard() })
            addView(row)
            addView(res)
        }
        val sv = ScrollView(this).apply {
            addView(root)
            background = GradientDrawable().apply { setColor(0xEE212121.toInt()); cornerRadius = dp(14).toFloat() }
        }
        val lp = WindowManager.LayoutParams(
            (sw * 0.96).toInt(), (sh * 0.55).toInt(), OVL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = sv
        wm.addView(sv, lp)
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
        arrow?.let { it.frame = false; it.invalidate() }
    }

    private fun analyze(fenText: String, res: TextView) {
        val pos = Pos.fromFen(fenText)
        if (pos == null) { res.text = "Неверный FEN"; return }
        prefs.edit().putString("fen", fenText.trim()).apply()
        val lvl = prefs.getInt("lvl", 5)
        res.text = "Думаю…"
        bg.execute {
            val r = engine.search(pos, 1 + lvl * 5 / 10, 200L + lvl * 400L, (10 - lvl) * 15)
            ui.post {
                val a = arrow
                if (r == null) {
                    res.text = "Нет легальных ходов (мат/пат)"
                    a?.let { it.from = -1; it.to = -1; it.invalidate() }
                } else {
                    val m = r.first
                    val promo = m shr 12
                    val name = sqName(m and 63) + sqName((m shr 6) and 63) + (if (promo > 0) "xxnbrq"[promo].toString() else "")
                    val sc = if (abs(r.second) > 90000) "мат" else String.format("%+.2f", r.second / 100.0)
                    res.text = "Лучший ход: $name  ($sc)"
                    a?.let { it.from = m and 63; it.to = (m shr 6) and 63; it.invalidate() }
                }
            }
        }
    }

    override fun onDestroy() {
        hidePanel()
        fab?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        arrow?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        synchronized(lock) { held?.close(); held = null }
        vd?.release()
        reader?.close()
        projection?.unregisterCallback(cb)
        projection?.stop()
        bg.shutdownNow()
        super.onDestroy()
    }
}

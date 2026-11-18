package com.example.chessoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var arrow: ArrowView
    private lateinit var bubble: TextView
    private lateinit var menu: LinearLayout
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var menuLp: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var fenEdit: EditText
    private var menuShown = false
    private var selectView: BoardSelectView? = null
    private var level = 1 // 0 = Легкий, 1 = Средний, 2 = Сложный
    private var playWhite = true
    private val depths = intArrayOf(1, 3, 4)
    private val handler = Handler(Looper.getMainLooper())

    // захват экрана
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun params(w: Int, h: Int, flags: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(w, h, overlayType(), flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("chess_overlay", "Chess overlay", NotificationManager.IMPORTANCE_LOW))
        }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "chess_overlay")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return n.setContentTitle("Chess overlay").setSmallIcon(android.R.drawable.ic_menu_compass).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "chess.PROJECTION") {
            val code = intent.getIntExtra("code", 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
            if (data != null) startProjection(code, data)
            return START_STICKY
        }
        try {
            startForeground(1, buildNotif())
        } catch (e: Exception) {
        }
        loadBoard()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("chess_overlay", Context.MODE_PRIVATE)
        playWhite = prefs.getBoolean("playWhite", true)
        level = prefs.getInt("level", 1).coerceIn(0, 2)
        buildArrow()
        buildBubble()
        buildMenu()
    }

    // ---------- доска (SharedPreferences) ----------

    private fun loadBoard() {
        // область доски автоматически подгружается при старте сервиса
        arrow.board = RectF(
            prefs.getFloat("bl", 0f), prefs.getFloat("bt", 0f),
            prefs.getFloat("br", 0f), prefs.getFloat("bb", 0f)
        )
        arrow.flip = playWhite.not()
        arrow.showFrame = menuShown
        arrow.invalidate()
    }

    private fun hasBoard() = arrow.board.width() > 100f && arrow.board.height() > 100f

    // ---------- окна ----------

    private fun buildArrow() {
        arrow = ArrowView(this)
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        wm.addView(arrow, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, f))
        loadBoard()
    }

    private fun roundBg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun makeDraggable(handle: View, lp: WindowManager.LayoutParams, target: View, onClick: (() -> Unit)?) {
        var dx = 0f
        var dy = 0f
        var sx = 0
        var sy = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt()
                    val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > dp(4) || Math.abs(my) > dp(4)) moved = true
                    if (moved) {
                        lp.x = sx + mx
                        lp.y = sy + my
                        wm.updateViewLayout(target, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (moved.not()) onClick?.invoke()
            }
            true
        }
    }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            text = "♞"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#EE1E1E1E"), 28)
        }
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        bubbleLp = params(dp(56), dp(56), f)
        bubbleLp.x = dp(8)
        bubbleLp.y = dp(200)
        wm.addView(bubble, bubbleLp)
        makeDraggable(bubble, bubbleLp, bubble) { toggleMenu() }
    }

    private fun setMenuFocusable(on: Boolean) {
        menuLp.flags = if (on) menuLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else menuLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            wm.updateViewLayout(menu, menuLp)
        } catch (e: Exception) {
        }
    }

    private fun btn(t: String, click: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        background = roundBg(Color.parseColor("#FF3A3A3A"), 10)
        minHeight = 0
        minimumHeight = dp(36)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { click() }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.WHITE)
    }

    /** Сегментированный переключатель (как RadioGroup, но красивее). */
    private fun segmented(labels: List<String>, selected: Int, onSel: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tvs = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, tv) in tvs.withIndex()) {
                tv.background = roundBg(Color.parseColor(if (i == sel) "#FF2E7D32" else "#FF3A3A3A"), 8)
            }
        }
        labels.forEachIndexed { i, t ->
            val tv = TextView(this).apply {
                text = t
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    paint(i)
                    onSel(i)
                }
            }
            tvs.add(tv)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            row.addView(tv, lp)
        }
        paint(selected)
        return row
    }

    private fun buildMenu() {
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.parseColor("#EE1E1E1E"), 16)
            visibility = View.GONE
        }
        // Заголовок — за него меню перетаскивается
        val header = TextView(this).apply {
            text = "♞ Шахматы  (тяни меня)"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = roundBg(Color.parseColor("#FF2E2E2E"), 16)
        }
        menu.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        menu.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(label("Играю за:"))
        body.addView(segmented(listOf("Белых", "Чёрных"), if (playWhite) 0 else 1) { i ->
            playWhite = i == 0
            prefs.edit().putBoolean("playWhite", playWhite).apply()
            arrow.flip = playWhite.not()
            arrow.move = null
            arrow.invalidate()
        })

        body.addView(label("Сложность:"))
        body.addView(segmented(listOf("Легкий", "Средний", "Сложный"), level) { i ->
            level = i
            prefs.edit().putInt("level", level).apply()
        })

        body.addView(btn("Выбрать доску") { startBoardSelect() })

        body.addView(label("FEN позиции:"))
        fenEdit = EditText(this).apply {
            setText(Position.START)
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    setMenuFocusable(true)
                    v.requestFocus()
                    handler.postDelayed({
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(v, 0)
                    }, 150)
                }
                false
            }
        }
        body.addView(fenEdit)

        body.addView(btn("Анализ") { analyze() })
        body.addView(btn("Очистить стрелку") {
            arrow.move = null
            arrow.invalidate()
            status.text = ""
        })
        body.addView(btn("Закрыть сервис") { stopSelf() })
        status = label("")
        body.addView(status)

        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        menuLp = params(dp(250), dp(300), f)
        menuLp.x = dp(70)
        menuLp.y = dp(200)
        menuLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.addView(menu, menuLp)
        makeDraggable(header, menuLp, menu, null)
    }

    private fun toggleMenu() {
        menuShown = menuShown.not()
        menu.visibility = if (menuShown) View.VISIBLE else View.GONE
        arrow.showFrame = menuShown
        arrow.invalidate()
        if (menuShown.not()) setMenuFocusable(false)
    }

    /** Показать/скрыть весь наш UI (нужно, чтобы он не попал в скриншот). Состояние меню не теряется. */
    private fun setUiVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        arrow.visibility = vis
        bubble.visibility = vis
        menu.visibility = if (v) (if (menuShown) View.VISIBLE else View.GONE) else View.INVISIBLE
    }

    // ---------- калибровка доски ----------

    private fun startBoardSelect() {
        if (selectView != null) return
        setMenuFocusable(false)
        setUiVisible(false)
        val sv = BoardSelectView(this, { r ->
            prefs.edit().putFloat("bl", r.left).putFloat("bt", r.top)
                .putFloat("br", r.right).putFloat("bb", r.bottom).commit()
            finishSelect()
            loadBoard()
            status.text = "Доска сохранена ✓ (${r.width().toInt()}x${r.height().toInt()})"
        }, {
            finishSelect()
            status.text = "Выбор отменён"
        })
        selectView = sv
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        wm.addView(sv, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, f))
    }

    private fun finishSelect() {
        selectView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        selectView = null
        setUiVisible(true) // меню возвращается на место без перезапуска сервиса
    }

    // ---------- захват экрана ----------

    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    private fun startProjection(code: Int, data: Intent) {
        try {
            val n = buildNotif()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, n)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { releaseProjection() }
                }
            }, handler)
            val sz = realSize()
            val ir = ImageReader.newInstance(sz[0], sz[1], PixelFormat.RGBA_8888, 2)
            vdisplay = mp.createVirtualDisplay(
                "chess_capture", sz[0], sz[1], resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler
            )
            projection = mp
            reader = ir
            status.text = "Захват экрана включён. Открой Chess.com и нажми Анализ"
        } catch (e: Exception) {
            status.text = "Ошибка захвата: ${e.message}"
        }
    }

    private fun releaseProjection() {
        try {
            vdisplay?.release()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        vdisplay = null
        reader = null
        projection = null
    }

    private fun requestCapture() {
        val i = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            status.text = "Не удалось запросить захват: ${e.message}"
        }
    }

    /** Прячет оверлей, берёт кадр экрана, вырезает область доски и сохраняет last_board.png. */
    private fun captureBoard(done: (String) -> Unit) {
        val ir = reader
        if (ir == null) {
            done("")
            return
        }
        val b = RectF(arrow.board)
        setUiVisible(false)
        handler.postDelayed({ grab(ir, b, 0, done) }, 300)
    }

    private fun grab(ir: ImageReader, b: RectF, attempt: Int, done: (String) -> Unit) {
        val img = try {
            ir.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) {
            if (attempt < 8) handler.postDelayed({ grab(ir, b, attempt + 1, done) }, 150)
            else {
                setUiVisible(true)
                done("Нет кадра экрана.\n")
            }
            return
        }
        var bmp: Bitmap? = null
        try {
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val l = b.left.toInt().coerceIn(0, img.width - 2)
            val t = b.top.toInt().coerceIn(0, img.height - 2)
            val w = b.width().toInt().coerceAtMost(img.width - l).coerceAtLeast(1)
            val h = b.height().toInt().coerceAtMost(img.height - t).coerceAtLeast(1)
            bmp = Bitmap.createBitmap(full, l, t, w, h)
            full.recycle()
        } catch (e: Exception) {
            bmp = null
        } finally {
            img.close()
        }
        setUiVisible(true) // меню остаётся на месте
        val out = bmp
        if (out == null) {
            done("Ошибка скрина.\n")
            return
        }
        Thread {
            var msg: String
            try {
                val f = File(getExternalFilesDir(null) ?: filesDir, "last_board.png")
                FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                msg = "Скрин доски сохранён (${out.width}x${out.height}):\n${f.absolutePath}\n"
            } catch (e: Exception) {
                msg = "Не удалось сохранить скрин.\n"
            }
            handler.post { done(msg) }
        }.start()
    }

    // ---------- анализ ----------

    private fun analyze() {
        setMenuFocusable(false)
        if (busy) return
        val pos = Position.fromFen(fenEdit.text.toString())
        if (pos == null) {
            status.text = "Неверный FEN"
            return
        }
        pos.white = playWhite // ищем лучший ход для выбранной стороны
        if (hasBoard().not()) {
            runEngine(pos, "Доска не выбрана — нажми «Выбрать доску».\n")
            return
        }
        if (reader == null) {
            requestCapture()
            runEngine(pos, "Разреши захват экрана и нажми Анализ ещё раз.\n")
            return
        }
        busy = true
        status.text = "Скриншот..."
        captureBoard { msg -> runEngine(pos, msg) }
    }

    private fun runEngine(pos: Position, prefix: String) {
        busy = true
        status.text = prefix + "Думаю..."
        val d = depths[level]
        Thread {
            val mv = Engine.best(pos, d)
            handler.post {
                arrow.move = mv
                arrow.flip = playWhite.not()
                arrow.invalidate()
                status.text = prefix + (if (mv == null) "Нет ходов" else "Лучший ход: $mv")
                busy = false
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProjection()
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        for (v in listOf<View?>(arrow, bubble, menu, selectView)) {
            try {
                if (v != null) wm.removeView(v)
            } catch (e: Exception) {
            }
        }
    }
}

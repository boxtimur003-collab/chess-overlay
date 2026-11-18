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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var arrow: ArrowView
    private lateinit var bubble: TextView
    private lateinit var menu: LinearLayout
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var menuLp: WindowManager.LayoutParams
    private lateinit var status: TextView
    private var menuShown = false
    private var selectView: BoardSelectView? = null
    private var level = 1 // 0 = Легкий, 1 = Средний, 2 = Сложный
    private var playWhite = true
    private val depths = intArrayOf(1, 3, 4)
    private val handler = Handler(Looper.getMainLooper())

    // захват экрана (кадры живут только в оперативной памяти)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var lastImg: Image? = null
    private var lastFrameAt = 0L
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

    /** Удаляем last_board.png, если он остался от прошлых версий. Новые версии скриншоты на диск не пишут. */
    private fun cleanupOldScreens() {
        try {
            File(getExternalFilesDir(null) ?: filesDir, "last_board.png").delete()
        } catch (e: Exception) {
        }
        try {
            File(filesDir, "last_board.png").delete()
        } catch (e: Exception) {
        }
    }

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
        cleanupOldScreens()
        BoardRecognizer.loadTemplates(prefs) // эталоны фигур остаются после перезапуска
        buildArrow()
        buildBubble()
        buildMenu()
    }

    // ---------- доска (SharedPreferences) ----------

    private fun loadBoard() {
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

    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
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

    /** Широкая плашка двигается только вверх/вниз. */
    private fun makeVDraggable(handle: View, lp: WindowManager.LayoutParams, target: View) {
        var dy = 0f
        var sy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dy = e.rawY; sy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = maxOf(0, realSize()[1] - target.height)
                    lp.y = (sy + (e.rawY - dy).toInt()).coerceIn(0, maxY)
                    try {
                        wm.updateViewLayout(target, lp)
                    } catch (ex: Exception) {
                    }
                }
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

    private fun btn(t: String, color: String = "#FF3A3A3A", click: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundBg(Color.parseColor(color), 10)
        minHeight = 0
        minimumHeight = dp(38)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { click() }
    }

    /** Сегментированный переключатель. */
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
                setPadding(dp(2), dp(9), dp(2), dp(9))
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
        // Горизонтальная широкая плашка: слева ручка (тянуть вверх/вниз), справа — все элементы
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Color.parseColor("#EE1E1E1E"), 16)
            visibility = View.GONE
        }
        val handle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#FF2E2E2E"), 16)
        }
        menu.addView(handle, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        menu.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // строка 1: цвет + сложность
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(segmented(listOf("♙ Белые", "♟ Чёрные"), if (playWhite) 0 else 1) { i ->
            playWhite = i == 0
            prefs.edit().putBoolean("playWhite", playWhite).apply()
            arrow.flip = playWhite.not()
            arrow.move = null
            arrow.invalidate()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        row1.addView(segmented(listOf("Легкий", "Средний", "Сложный"), level) { i ->
            level = i
            prefs.edit().putInt("level", level).apply()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        content.addView(row1)

        fun lpB(w: Float): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            lp.setMargins(dp(2), dp(6), dp(2), dp(2))
            return lp
        }

        // строка 2: настройка
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Запомнить эталоны") { learnTemplates() }, lpB(1.8f))
        row2.addView(btn("Доска") { startBoardSelect() }, lpB(1f))
        row2.addView(btn("Закрыть") { stopSelf() }, lpB(1f))
        content.addView(row2)

        // строка 3: главная кнопка
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(btn("Сканировать", "#FF2E7D32") { scan() }, lpB(1f))
        content.addView(row3)

        status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 5
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        content.addView(status)

        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val sz = realSize()
        menuLp = params(sz[0] - dp(16), WindowManager.LayoutParams.WRAP_CONTENT, f)
        menuLp.x = dp(8)
        menuLp.y = sz[1] - dp(250)
        wm.addView(menu, menuLp)
        makeVDraggable(handle, menuLp, menu)
    }

    private fun toggleMenu() {
        menuShown = menuShown.not()
        menu.visibility = if (menuShown) View.VISIBLE else View.GONE
        arrow.showFrame = menuShown
        arrow.invalidate()
    }

    /** Показать/скрыть весь наш UI (чтобы он не попал в скриншот). Состояние меню не теряется. */
    private fun setUiVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        arrow.visibility = vis
        bubble.visibility = vis
        menu.visibility = if (v) (if (menuShown) View.VISIBLE else View.GONE) else View.INVISIBLE
    }

    // ---------- выбор доски: один вертикальный свайп ----------

    private fun startBoardSelect() {
        if (selectView != null) return
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
        setUiVisible(true)
    }

    // ---------- захват экрана ----------

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
            val ir = ImageReader.newInstance(sz[0], sz[1], PixelFormat.RGBA_8888, 3)
            // держим самый свежий кадр и время его прихода — так снимок точно сделан после скрытия UI
            ir.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        lastImg?.close()
                        lastImg = img
                        lastFrameAt = SystemClock.uptimeMillis()
                    }
                } catch (e: Exception) {
                }
            }, handler)
            vdisplay = mp.createVirtualDisplay(
                "chess_capture", sz[0], sz[1], resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler
            )
            projection = mp
            reader = ir
            status.text = "Захват экрана включён. Открой Chess.com и нажми нужную кнопку"
        } catch (e: Exception) {
            status.text = "Ошибка захвата: ${e.message}"
        }
    }

    private fun releaseProjection() {
        try {
            lastImg?.close()
        } catch (e: Exception) {
        }
        lastImg = null
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

    /** Прячет UI на 500 мс, берёт свежий кадр и вырезает область доски. Всё в памяти, на диск ничего не пишется. */
    private fun captureBoard(done: (Bitmap?, String) -> Unit) {
        if (reader == null) {
            done(null, "Захват экрана не включён.")
            return
        }
        val b = RectF(arrow.board)
        val hideAt = SystemClock.uptimeMillis()
        setUiVisible(false)
        handler.postDelayed({ grab(b, hideAt, 0, done) }, 500)
    }

    private fun grab(b: RectF, hideAt: Long, attempt: Int, done: (Bitmap?, String) -> Unit) {
        val cur = lastImg
        val fresh = cur != null && lastFrameAt >= hideAt + 80
        if (fresh.not() && attempt < 10) {
            handler.postDelayed({ grab(b, hideAt, attempt + 1, done) }, 150)
            return
        }
        if (cur == null) {
            setUiVisible(true)
            done(null, "Нет кадра экрана.")
            return
        }
        lastImg = null
        var bmp: Bitmap? = null
        try {
            val plane = cur.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(bw, cur.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val l = b.left.toInt().coerceIn(0, cur.width - 2)
            val t = b.top.toInt().coerceIn(0, cur.height - 2)
            val w = b.width().toInt().coerceAtMost(cur.width - l).coerceAtLeast(1)
            val h = b.height().toInt().coerceAtMost(cur.height - t).coerceAtLeast(1)
            bmp = Bitmap.createBitmap(full, l, t, w, h)
            full.recycle()
        } catch (e: Exception) {
            bmp = null
        } finally {
            cur.close() // кадр освобождён сразу
        }
        setUiVisible(true) // всё возвращается на место
        val out = bmp
        if (out == null) done(null, "Ошибка скриншота.") else done(out, "")
    }

    // ---------- общие проверки перед снимком ----------

    private fun prepare(): Boolean {
        if (busy) return false
        if (hasBoard().not()) {
            status.text = "Сначала нажми «Доска» и проведи пальцем сверху вниз."
            return false
        }
        if (reader == null) {
            requestCapture()
            status.text = "Разреши захват экрана и снова нажми кнопку."
            return false
        }
        return true
    }

    // ---------- «Запомнить эталоны» ----------

    private fun learnTemplates() {
        if (prepare().not()) return
        busy = true
        arrow.move = null
        arrow.invalidate()
        status.text = "Скриншот..."
        captureBoard { bmp, msg ->
            if (bmp == null) {
                busy = false
                status.text = msg
            } else {
                status.text = "Запоминаю эталоны..."
                val white = playWhite
                Thread {
                    val rec = BoardRecognizer.learn(bmp, white, prefs)
                    bmp.recycle() // скриншот удалён из памяти
                    handler.post {
                        status.text = rec.msg
                        busy = false
                    }
                }.start()
            }
        }
    }

    // ---------- «Сканировать» ----------

    private fun scan() {
        if (busy) return
        if (BoardRecognizer.hasTemplates().not()) {
            status.text = "Сначала нажми \"Запомнить эталоны\""
            return
        }
        if (prepare().not()) return
        busy = true
        arrow.move = null
        arrow.invalidate()
        status.text = "Скриншот..."
        captureBoard { bmp, msg ->
            if (bmp == null) {
                busy = false
                status.text = msg
            } else {
                status.text = "Распознаю доску..."
                recognizeAndPlay(bmp)
            }
        }
    }

    private fun recognizeAndPlay(bmp: Bitmap) {
        val white = playWhite
        Thread {
            val rec = BoardRecognizer.recognize(bmp, white)
            bmp.recycle() // распознали — скриншот сразу удаляем из памяти
            handler.post {
                val fen = rec.fen
                val pos = if (fen != null) Position.fromFen(fen) else null
                if (fen == null) {
                    status.text = rec.msg
                    busy = false
                } else if (pos == null) {
                    status.text = "Получился некорректный FEN: $fen"
                    busy = false
                } else {
                    pos.white = white // ищем лучший ход для выбранной стороны
                    runEngine(pos, rec.msg + " ")
                }
            }
        }.start()
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
        cleanupOldScreens()
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

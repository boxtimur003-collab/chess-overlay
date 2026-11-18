package com.example.chessoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

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
    private var depth = 3
    private var playWhite = true
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("chess_overlay", "Chess overlay", NotificationManager.IMPORTANCE_LOW))
            }
            val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "chess_overlay")
            else @Suppress("DEPRECATION") Notification.Builder(this)
            n.setContentTitle("Chess overlay").setSmallIcon(android.R.drawable.ic_menu_compass)
            startForeground(1, n.build())
        } catch (e: Exception) {
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("chess_overlay", Context.MODE_PRIVATE)
        playWhite = prefs.getBoolean("playWhite", true)
        depth = prefs.getInt("depth", 3)
        buildArrow()
        buildBubble()
        buildMenu()
    }

    private fun loadBoard() {
        arrow.board = RectF(
            prefs.getFloat("bl", 0f), prefs.getFloat("bt", 0f),
            prefs.getFloat("br", 0f), prefs.getFloat("bb", 0f)
        )
        arrow.flip = playWhite.not()
        arrow.invalidate()
    }

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
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbW = RadioButton(this).apply { text = "Белых"; setTextColor(Color.WHITE); id = 101 }
        val rbB = RadioButton(this).apply { text = "Чёрных"; setTextColor(Color.WHITE); id = 102 }
        rg.addView(rbW)
        rg.addView(rbB)
        rg.check(if (playWhite) 101 else 102)
        rg.setOnCheckedChangeListener { _, id ->
            playWhite = id == 101
            prefs.edit().putBoolean("playWhite", playWhite).apply()
            arrow.flip = playWhite.not()
            arrow.move = null
            arrow.invalidate()
        }
        body.addView(rg)

        val depthLabel = label("Глубина: $depth")
        body.addView(depthLabel)
        body.addView(SeekBar(this).apply {
            max = 4
            progress = depth - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    depth = p + 1
                    depthLabel.text = "Глубина: $depth"
                    prefs.edit().putInt("depth", depth).apply()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
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
        if (menuShown.not()) setMenuFocusable(false)
    }

    private fun analyze() {
        setMenuFocusable(false)
        val pos = Position.fromFen(fenEdit.text.toString())
        if (pos == null) {
            status.text = "Неверный FEN"
            return
        }
        pos.white = playWhite // ищем лучший ход для выбранной стороны
        status.text = "Думаю..."
        val d = depth
        Thread {
            val mv = Engine.best(pos, d)
            handler.post {
                arrow.move = mv
                arrow.flip = playWhite.not()
                arrow.invalidate()
                status.text = if (mv == null) "Нет ходов" else "Лучший ход: $mv"
            }
        }.start()
    }

    private fun startBoardSelect() {
        setMenuFocusable(false)
        menu.visibility = View.GONE
        bubble.visibility = View.GONE
        arrow.visibility = View.GONE
        val sv = BoardSelectView(this) { r ->
            prefs.edit().putFloat("bl", r.left).putFloat("bt", r.top)
                .putFloat("br", r.right).putFloat("bb", r.bottom).apply()
            finishSelect()
            loadBoard()
            status.text = "Доска сохранена"
        }
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
        bubble.visibility = View.VISIBLE
        arrow.visibility = View.VISIBLE
        menu.visibility = if (menuShown) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        for (v in listOf<View?>(arrow, bubble, menu, selectView)) {
            try {
                if (v != null) wm.removeView(v)
            } catch (e: Exception) {
            }
        }
    }
}

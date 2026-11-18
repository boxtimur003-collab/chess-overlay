package com.example.chessoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Служба специальных возможностей: делает ход на экране двумя нажатиями (откуда -> куда).
 * Включается вручную: Настройки -> Специальные возможности -> Chess AutoPlayer.
 */
class AutoPlayer : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutoPlayer? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun tap(x: Float, y: Float, cb: (Boolean) -> Unit) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 70L))
            .build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cb(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cb(false)
            }
        }, null)
        if (ok.not()) cb(false)
    }

    /** Нажать на клетку «откуда», через 250 мс на клетку «куда»; при превращении — ещё раз на «куда» (ферзь). */
    fun playMove(x1: Float, y1: Float, x2: Float, y2: Float, promo: Boolean, cb: (Boolean) -> Unit) {
        tap(x1, y1) { ok1 ->
            if (ok1.not()) {
                cb(false)
            } else {
                handler.postDelayed({
                    tap(x2, y2) { ok2 ->
                        if (ok2.not()) {
                            cb(false)
                        } else if (promo) {
                            handler.postDelayed({ tap(x2, y2) { ok3 -> cb(ok3) } }, 450L)
                        } else {
                            cb(true)
                        }
                    }
                }, 250L)
            }
        }
    }
}

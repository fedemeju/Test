package com.fedemeju.colortap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * Servicio de accesibilidad: dibuja la burbuja flotante y el modo de ajuste,
 * y ejecuta los toques en pantalla.
 */
class ClickerService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickerService? = null
        @Volatile var BORDER_PX = 8
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var density = 1f

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private var box: BoxView? = null
    private var boxLp: WindowManager.LayoutParams? = null
    private var cross: CrossView? = null
    private var crossLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var pctText: TextView? = null
    private var swatch: View? = null

    private fun dp(v: Float) = (v * density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        density = resources.displayMetrics.density
        BORDER_PX = dp(3f)
        Engine.load(this)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        exitEdit(save = false)
        removeBubble()
        instance = null
        super.onDestroy()
    }

    // ---------- Toques ----------
    fun tap(x: Float, y: Float) {
        main.post {
            val path = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            dispatchGesture(g, null, null)
        }
    }

    // ---------- Estado general ----------
    /** Muestra la burbuja sólo mientras la captura está activa. */
    fun refresh() {
        if (Engine.capturing) showBubble() else { exitEdit(save = false); removeBubble() }
        bubble?.invalidate()
    }

    private fun lp(w: Int, h: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    // ---------- Burbuja ----------
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(56f)
        val p = lp(size, size).apply { x = dp(8f); y = screenH() / 3 }
        val v = BubbleView(this)
        bubble = v; bubbleLp = p
        wm.addView(v, p)
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun onBubbleTap() {
        if (Engine.editing) { exitEdit(save = true); return }
        val c = Engine.cfg
        if (!c.hasRegion || !c.hasTarget) {
            Toast.makeText(this, "Primero mantené apretada la burbuja para elegir el sector y el punto", Toast.LENGTH_LONG).show()
            return
        }
        Engine.setActive(this, !Engine.active)
    }

    private fun onBubbleLongPress() {
        if (Engine.editing) exitEdit(save = true) else enterEdit()
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var downTime = 0L
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPress = Runnable { if (!dragging) { dragging = true; performHapticFeedback(0); onBubbleLongPress() } }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            paint.style = Paint.Style.FILL
            paint.color = when {
                Engine.editing -> Color.rgb(255, 179, 0)
                Engine.active -> Color.rgb(67, 160, 71)
                else -> Color.rgb(97, 97, 97)
            }
            paint.alpha = 220
            canvas.drawCircle(r, r, r - dp(2f), paint)
            paint.alpha = 255
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            canvas.drawCircle(r, r, r - dp(3f), paint)
            paint.style = Paint.Style.FILL
            if (Engine.editing) {
                paint.textSize = dp(13f).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText("OK", r, r + dp(5f), paint)
            } else if (Engine.active) {
                // pausa
                val w = dp(5f).toFloat(); val h = dp(18f).toFloat()
                canvas.drawRect(r - w * 1.6f, r - h / 2, r - w * 0.6f, r + h / 2, paint)
                canvas.drawRect(r + w * 0.6f, r - h / 2, r + w * 1.6f, r + h / 2, paint)
            } else {
                // play
                val s = dp(10f).toFloat()
                val tri = Path().apply {
                    moveTo(r - s * 0.7f, r - s); lineTo(r - s * 0.7f, r + s); lineTo(r + s, r); close()
                }
                canvas.drawPath(tri, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = bubbleLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    downTime = System.currentTimeMillis(); dragging = false
                    postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true; removeCallbacks(longPress)
                    }
                    if (dragging && System.currentTimeMillis() - downTime >= 0) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!dragging) onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------- Modo ajuste ----------
    private fun enterEdit() {
        if (Engine.editing) return
        Engine.editing = true
        Engine.active = false
        Engine.stateChanged(this)

        val c = Engine.cfg
        val sw = screenW(); val sh = screenH()
        val def = dp(90f)
        val bw = if (c.hasRegion) c.rw else def
        val bh = if (c.hasRegion) c.rh else def
        val bx = if (c.hasRegion) c.rx else sw / 2 - def / 2
        val by = if (c.hasRegion) c.ry else sh / 2 - def / 2

        // Marco del sector
        val bLp = lp(bw, bh).apply { x = bx; y = by }
        val b = BoxView(this)
        box = b; boxLp = bLp
        wm.addView(b, bLp)

        // Mira del punto de toque
        val cs = dp(48f)
        val tx = if (c.hasTarget) c.tx else sw / 2
        val ty = if (c.hasTarget) c.ty else sh / 2 + def
        val cLp = lp(cs, cs).apply { x = tx - cs / 2; y = ty - cs / 2 }
        val cr = CrossView(this)
        cross = cr; crossLp = cLp
        wm.addView(cr, cLp)

        // Panel de herramientas
        showPanel()
        bubble?.invalidate()
        main.post(pctUpdater)
    }

    private fun showPanel() {
        val pnl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 30, 30, 36)); cornerRadius = dp(12f).toFloat()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sv = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Engine.cfg.color); cornerRadius = dp(4f).toFloat(); setStroke(dp(1f), Color.WHITE)
            }
        }
        swatch = sv
        top.addView(sv, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8f), 0, 0, 0)
            text = "Coincide: --%   (arrastrá acá para mover)"
        }
        pctText = tv
        top.addView(tv)
        pnl.addView(top)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false; setOnClickListener { onClick() }
        }
        row.addView(btn("Tomar color") {
            Engine.sampleRequest = true
            saveGeometry()
        })
        row.addView(btn("Probar toque") { testTap() })
        row.addView(btn("Guardar") { exitEdit(save = true) })
        pnl.addView(row)

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(12f); y = dp(40f)
        }
        // Arrastrar el panel desde el texto
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        top.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt()
                    try { wm.updateViewLayout(pnl, p) } catch (_: Exception) {}
                }
            }
            true
        }
        panel = pnl; panelLp = p
        wm.addView(pnl, p)
    }

    private val pctUpdater = object : Runnable {
        override fun run() {
            if (!Engine.editing) return
            saveGeometry()
            pctText?.text = "Coincide: ${Engine.lastPct}%  (mínimo ${Engine.cfg.minPct}%)"
            main.postDelayed(this, 250)
        }
    }

    fun onColorSampled(col: Int) {
        (swatch?.background as? GradientDrawable)?.setColor(col)
        Toast.makeText(this, String.format("Color tomado: #%06X", col and 0xFFFFFF), Toast.LENGTH_SHORT).show()
    }

    private fun testTap() {
        val cr = cross ?: return
        val p = crossLp ?: return
        saveGeometry()
        // Hacer la mira "transparente" al toque, tocar y restaurar
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(cr, p) } catch (_: Exception) {}
        val c = Engine.cfg
        main.postDelayed({
            tap(c.tx.toFloat(), c.ty.toFloat())
            main.postDelayed({
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(cr, p) } catch (_: Exception) {}
            }, 200)
        }, 80)
    }

    /** Guarda la posición actual del marco y la mira. */
    private fun saveGeometry() {
        val b = box ?: return
        val cr = cross ?: return
        val bl = IntArray(2); b.getLocationOnScreen(bl)
        val cl = IntArray(2); cr.getLocationOnScreen(cl)
        if (b.width == 0 || cr.width == 0) return
        val nc = Engine.cfg.copy(
            rx = bl[0], ry = bl[1], rw = b.width, rh = b.height,
            tx = cl[0] + cr.width / 2, ty = cl[1] + cr.height / 2
        )
        if (nc != Engine.cfg) Engine.save(this, nc)
    }

    private fun exitEdit(save: Boolean) {
        if (!Engine.editing && box == null) return
        if (save) saveGeometry()
        main.removeCallbacks(pctUpdater)
        listOf(box, cross, panel).forEach { v -> v?.let { try { wm.removeView(it) } catch (_: Exception) {} } }
        box = null; cross = null; panel = null; pctText = null; swatch = null
        Engine.editing = false
        Engine.stateChanged(this)
        bubble?.invalidate()
        if (save) Toast.makeText(this, "Guardado. Tocá la burbuja para activar.", Toast.LENGTH_SHORT).show()
    }

    /** Marco del sector: arrastrar para mover, esquina inferior derecha para cambiar tamaño. */
    @SuppressLint("ViewConstructor")
    private inner class BoxView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.rgb(255, 213, 79)
            strokeWidth = BORDER_PX.toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(8f).toFloat(), dp(5f).toFloat()), 0f)
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.rgb(255, 213, 79); strokeWidth = BORDER_PX * 2f
        }
        private var mode = 0 // 1 mover, 2 redimensionar
        private var dx = 0f; private var dy = 0f
        private var sx = 0; private var sy = 0; private var sw = 0; private var sh = 0

        override fun onDraw(canvas: Canvas) {
            val h = BORDER_PX / 2f
            canvas.drawRect(h, h, width - h, height - h, paint)
            val len = dp(16f).toFloat()
            val w = width.toFloat(); val hh = height.toFloat()
            canvas.drawLine(w - len, hh - BORDER_PX, w - BORDER_PX, hh - BORDER_PX, corner)
            canvas.drawLine(w - BORDER_PX, hh - len, w - BORDER_PX, hh - BORDER_PX, corner)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = boxLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y; sw = width; sh = height
                    val zone = max(dp(28f), minOf(width, height) / 3)
                    mode = if (e.x > width - zone && e.y > height - zone) 2 else 1
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (mode == 1) { p.x = sx + mx; p.y = sy + my }
                    else { p.width = max(dp(16f), sw + mx); p.height = max(dp(16f), sh + my) }
                    try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> saveGeometry()
            }
            return true
        }
    }

    /** Mira que marca dónde se va a tocar. */
    @SuppressLint("ViewConstructor")
    private inner class CrossView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.rgb(0, 229, 255); strokeWidth = dp(2.5f).toFloat()
        }
        private var dx = 0f; private var dy = 0f; private var sx = 0; private var sy = 0

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            canvas.drawCircle(c, c, c - dp(4f), paint)
            canvas.drawLine(c, dp(2f).toFloat(), c, width - dp(2f).toFloat(), paint)
            canvas.drawLine(dp(2f).toFloat(), c, width - dp(2f).toFloat(), c, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = crossLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt()
                    try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> saveGeometry()
            }
            return true
        }
    }

    /** Abrir la app (por ejemplo, desde la burbuja o el botón rápido). */
    fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

package com.fedemeju.colortap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var infoText: TextView? = null
    private var swatch: View? = null
    private var preview: ImageView? = null
    private var modeBtn: Button? = null

    private var flashUntil = 0L

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
        hideMenu()
        exitEdit(save = false)
        removeBubble()
        instance = null
        super.onDestroy()
    }

    // ---------- Toques ----------
    fun tap(x: Float, y: Float) = tapMany(floatArrayOf(x), floatArrayOf(y))

    /**
     * Uno o varios toques simultáneos (un dedo por blanco).
     * Avisa a Engine cuando termina; si Android lo cancela, Engine reintenta esos blancos.
     */
    fun tapMany(xs: FloatArray, ys: FloatArray) {
        main.post {
            val n = min(xs.size, GestureDescription.getMaxStrokeCount())
            val b = GestureDescription.Builder()
            for (k in 0 until n) {
                val path = Path().apply { moveTo(xs[k], ys[k]) }
                b.addStroke(GestureDescription.StrokeDescription(path, 0, Engine.TAP_MS))
            }
            val ok = try {
                dispatchGesture(b.build(), object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { Engine.onGestureDone(xs, ys, true) }
                    override fun onCancelled(d: GestureDescription?) { Engine.onGestureDone(xs, ys, false) }
                }, null)
            } catch (_: Exception) { false }
            if (!ok) Engine.onGestureDone(xs, ys, false)
            flashUntil = System.currentTimeMillis() + 120
            bubble?.invalidate()
            main.postDelayed({ bubble?.invalidate() }, 130)
        }
    }

    // ---------- Estado general ----------
    fun refresh() {
        if (Engine.capturing) showBubble() else { hideMenu(); exitEdit(save = false); removeBubble() }
        bubble?.invalidate()
        updateExclude()
    }

    /** Le dice al detector qué zonas son nuestras (para no detectarlas). */
    private fun updateExclude() {
        val list = ArrayList<Rect>()
        for (v in listOf<View?>(bubble, panel)) {
            if (v == null || v.width == 0) continue
            val l = IntArray(2); v.getLocationOnScreen(l)
            list.add(Rect(l[0], l[1], l[0] + v.width, l[1] + v.height))
        }
        Engine.exclude = list
    }

    private fun lp(w: Int, h: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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

    private fun screenW() = if (Engine.screenW > 0) Engine.screenW else resources.displayMetrics.widthPixels
    private fun screenH() = if (Engine.screenH > 0) Engine.screenH else resources.displayMetrics.heightPixels

    // ---------- Burbuja ----------
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52f)
        val p = lp(size, size).apply { x = dp(4f); y = screenH() / 3 }
        val v = BubbleView(this)
        bubble = v; bubbleLp = p
        wm.addView(v, p)
        main.postDelayed({ updateExclude() }, 300)
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        updateExclude()
    }

    private fun onBubbleTap() {
        if (menu != null) { hideMenu(); return }
        if (Engine.editing) { exitEdit(save = true); return }
        if (Engine.active) {
            Engine.setActive(this, false)
            Toast.makeText(this, "ColorTap en pausa", Toast.LENGTH_SHORT).show()
            return
        }
        showMenu()
    }

    private fun onBubbleLongPress() {
        hideMenu()
        if (Engine.editing) exitEdit(save = true) else enterEdit()
    }

    private fun start() {
        if (!Engine.cfg.ready) {
            Toast.makeText(this, "Primero elegí la zona", Toast.LENGTH_SHORT).show()
            return
        }
        Engine.setActive(this, true)
        val c = Engine.cfg
        Toast.makeText(this, if (c.full) "ACTIVO en toda la pantalla" else "ACTIVO en la zona elegida", Toast.LENGTH_SHORT).show()
    }

    // ---------- Menú de la burbuja ----------
    private var menu: LinearLayout? = null

    private fun showMenu() {
        if (menu != null) return
        val c = Engine.cfg
        val m = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 30, 30, 36)); cornerRadius = dp(12f).toFloat()
            }
        }
        fun item(label: String, onClick: () -> Unit) {
            m.addView(Button(this).apply {
                text = label; textSize = 14f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { hideMenu(); onClick() }
            }, LinearLayout.LayoutParams(dp(220f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val zona = when {
            c.full -> "toda la pantalla"
            c.hasRegion -> "zona guardada"
            else -> null
        }
        if (zona != null) item("▶  Empezar ($zona)") { start() }
        item("🔀  " + modeLabel() + " (cambiar)") {
            toggleMode()
            Toast.makeText(this, modeLabel(), Toast.LENGTH_SHORT).show()
        }
        item("✏️  Editar zona") { enterEdit() }
        item("⛶  Toda la pantalla y empezar") {
            Engine.save(this, Engine.cfg.copy(full = true))
            start()
        }
        item("✕  Cerrar") {}

        val bl = bubbleLp
        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            val bx = bl?.x ?: 0; val by = bl?.y ?: 0
            x = if (bx > screenW() / 2) max(0, bx - dp(232f)) else bx + dp(58f)
            y = max(0, min(by, screenH() - dp(260f)))
        }
        m.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }
        menu = m
        wm.addView(m, p)
    }

    private fun hideMenu() {
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPress = Runnable { if (!dragging) { dragging = true; performHapticFeedback(0); onBubbleLongPress() } }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            paint.style = Paint.Style.FILL
            // Gris / azul / amarillo: nunca colores que se confundan con lo que se busca
            paint.color = when {
                Engine.editing -> Color.rgb(255, 179, 0)
                System.currentTimeMillis() < flashUntil -> Color.WHITE
                Engine.active -> Color.rgb(30, 136, 229)
                else -> Color.rgb(97, 97, 97)
            }
            paint.alpha = 225
            canvas.drawCircle(r, r, r - dp(2f), paint)
            paint.alpha = 255
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            canvas.drawCircle(r, r, r - dp(3f), paint)
            paint.style = Paint.Style.FILL
            if (System.currentTimeMillis() < flashUntil) paint.color = Color.rgb(30, 136, 229)
            if (Engine.editing) {
                paint.textSize = dp(13f).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText("OK", r, r + dp(5f), paint)
            } else if (Engine.active) {
                val w = dp(5f).toFloat(); val h = dp(18f).toFloat()
                canvas.drawRect(r - w * 1.6f, r - h / 2, r - w * 0.6f, r + h / 2, paint)
                canvas.drawRect(r + w * 0.6f, r - h / 2, r + w * 1.6f, r + h / 2, paint)
            } else {
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
                    dragging = false
                    postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true; removeCallbacks(longPress)
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!dragging) onBubbleTap()
                    main.postDelayed({ updateExclude() }, 100)
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
        val bw: Int; val bh: Int; val bx: Int; val by: Int
        if (c.full) {
            bw = sw; bh = sh; bx = 0; by = 0
        } else if (c.hasRegion && c.rx >= 0 && c.rx + c.rw <= sw + 4 && c.ry + c.rh <= sh + 4) {
            bw = c.rw; bh = c.rh; bx = c.rx; by = c.ry
        } else {
            // por defecto: casi toda la pantalla
            bw = (sw * 0.8f).toInt(); bh = (sh * 0.7f).toInt(); bx = (sw - bw) / 2; by = (sh - bh) / 2
        }

        val bLp = lp(bw, bh).apply { x = bx; y = by }
        val b = BoxView(this)
        box = b; boxLp = bLp
        wm.addView(b, bLp)

        if (c.mode == Cfg.MODE_FIXED) showCross()
        showPanel()
        bubble?.invalidate()
        main.postDelayed({ saveGeometry() }, 200)
        main.post(infoUpdater)
    }

    private fun showCross() {
        if (cross != null) return
        val c = Engine.cfg
        val cs = dp(48f)
        val tx = if (c.hasTarget) c.tx else screenW() / 2
        val ty = if (c.hasTarget) c.ty else screenH() / 2
        val cLp = lp(cs, cs).apply { x = tx - cs / 2; y = ty - cs / 2 }
        val cr = CrossView(this)
        cross = cr; crossLp = cLp
        wm.addView(cr, cLp)
    }

    private fun hideCross() {
        cross?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cross = null
    }

    private fun showPanel() {
        val pnl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 30, 30, 36)); cornerRadius = dp(12f).toFloat()
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
            setTextColor(Color.WHITE); textSize = 12.5f; setPadding(dp(8f), 0, 0, 0)
            text = "Analizando…"
        }
        infoText = tv
        top.addView(tv)
        pnl.addView(top)

        // Vista previa: lo que ve la app dentro del marco (verde = detectado)
        val pv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.BLACK)
        }
        pv.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val d = (v as ImageView).drawable
                if (d != null && d.intrinsicWidth > 0) {
                    // convertir toque en la vista → posición en la imagen (FIT_CENTER)
                    val s = min(v.width.toFloat() / d.intrinsicWidth, v.height.toFloat() / d.intrinsicHeight)
                    val iw = d.intrinsicWidth * s; val ih = d.intrinsicHeight * s
                    val ox = (v.width - iw) / 2; val oy = (v.height - ih) / 2
                    val fx = (e.x - ox) / iw; val fy = (e.y - oy) / ih
                    if (fx in 0f..1f && fy in 0f..1f) {
                        Engine.pickFromPreview(this, fx, fy)?.let { onColorSampled(it) }
                    }
                }
            }
            true
        }
        preview = pv
        pnl.addView(pv, LinearLayout.LayoutParams(dp(200f), dp(130f)).apply { topMargin = dp(6f) })
        pnl.addView(TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 11f
            text = "Tocá un círculo en esta vista para tomar su color"
        })

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 11.5f; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { onClick() }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mb = btn(modeLabel()) { toggleMode() }
        modeBtn = mb
        row1.addView(mb)
        row1.addView(btn("Pantalla entera") { fullScreenBox() })
        pnl.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Probar toque") { testTap() })
        row2.addView(btn("Guardar") { exitEdit(save = true) })
        row2.addView(btn("▶ Empezar") { exitEdit(save = true, quiet = true); start() })
        pnl.addView(row2)

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(8f); y = dp(30f)
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        top.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt()
                    try { wm.updateViewLayout(pnl, p) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> updateExclude()
            }
            true
        }
        panel = pnl; panelLp = p
        wm.addView(pnl, p)
        main.postDelayed({ updateExclude() }, 300)
    }

    private fun modeLabel() = when (Engine.cfg.mode) {
        Cfg.MODE_FIXED -> "Modo: punto fijo"
        Cfg.MODE_LANES -> "Modo: carriles"
        Cfg.MODE_MATH -> "Modo: math quiz"
        else -> "Modo: tocar el color"
    }

    private fun toggleMode() {
        saveGeometry()
        val c = Engine.cfg
        val nm = when (c.mode) {
            Cfg.MODE_ON_COLOR -> Cfg.MODE_FIXED
            Cfg.MODE_FIXED -> Cfg.MODE_LANES
            Cfg.MODE_LANES -> Cfg.MODE_MATH
            else -> Cfg.MODE_ON_COLOR
        }
        var nc = c.copy(mode = nm)
        when (nm) {
            Cfg.MODE_FIXED -> {
                showCross()
                Toast.makeText(this, "Punto fijo: mové la mira celeste adonde querés que toque", Toast.LENGTH_LONG).show()
            }
            Cfg.MODE_LANES -> {
                hideCross()
                nc = nc.copy(color = Cfg.PINK, hueTol = 22)
                (swatch?.background as? GradientDrawable)?.setColor(Cfg.PINK)
                Toast.makeText(this, "Carriles: el marco tiene que cubrir el área de juego de lado a lado. Verde = bloques, azul = naves.", Toast.LENGTH_LONG).show()
            }
            Cfg.MODE_MATH -> {
                hideCross()
                Toast.makeText(this, "Math quiz: poné el marco sobre la cuenta. Las opciones se buscan debajo del marco.", Toast.LENGTH_LONG).show()
            }
            else -> hideCross()
        }
        Engine.save(this, nc)
        modeBtn?.text = modeLabel()
        panel?.let { pn -> panelLp?.let { pl -> try { wm.removeView(pn); wm.addView(pn, pl) } catch (_: Exception) {} } }
    }

    private fun fullScreenBox() {
        val b = box ?: return
        val p = boxLp ?: return
        p.x = 0; p.y = 0; p.width = screenW(); p.height = screenH()
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
        // reordenar para que el panel y la mira queden encima
        for ((v, l) in listOf(cross to crossLp, panel to panelLp)) {
            if (v != null && l != null) try { wm.removeView(v); wm.addView(v, l) } catch (_: Exception) {}
        }
        main.postDelayed({ saveGeometry(); updateExclude() }, 250)
    }

    private val infoUpdater = object : Runnable {
        override fun run() {
            if (!Engine.editing) return
            saveGeometry()
            val txt = when {
                Engine.frames == 0 -> "⚠️ No llegan imágenes de la pantalla.\nDetené y volvé a iniciar la captura."
                Engine.black -> "⚠️ La captura sale NEGRA: este juego\nbloquea la captura de pantalla."
                Engine.cfg.mode == Cfg.MODE_LANES -> Engine.laneInfo.ifEmpty { "Buscando naves…" }
                Engine.cfg.mode == Cfg.MODE_MATH -> MathSolver.info.ifEmpty { "Leyendo…" }
                else -> "Círculos detectados: ${Engine.lastBlobs}\nCaptura ${Engine.capW}×${Engine.capH}"
            }
            infoText?.text = txt
            main.postDelayed(this, 300)
        }
    }

    fun onPreview(bmp: Bitmap) {
        val pv = preview ?: return
        val d = BitmapDrawable(resources, bmp).apply { paint.isFilterBitmap = false }
        pv.setImageDrawable(d)
    }

    fun onColorSampled(col: Int) {
        (swatch?.background as? GradientDrawable)?.setColor(col)
        Toast.makeText(this, String.format("Color elegido: #%06X", col and 0xFFFFFF), Toast.LENGTH_SHORT).show()
    }

    private fun testTap() {
        val c = Engine.cfg
        val x: Float; val y: Float
        if (c.mode == Cfg.MODE_FIXED) {
            saveGeometry()
            x = c.tx.toFloat(); y = c.ty.toFloat()
        } else {
            Toast.makeText(this, "En modo \"tocar el color\" se toca sobre cada círculo. Guardá y activá la burbuja.", Toast.LENGTH_LONG).show()
            return
        }
        val cr = cross ?: return
        val p = crossLp ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(cr, p) } catch (_: Exception) {}
        main.postDelayed({
            tap(x, y)
            main.postDelayed({
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(cr, p) } catch (_: Exception) {}
            }, 200)
        }, 80)
    }

    /** Guarda la posición actual del marco (y de la mira, si está). */
    private fun saveGeometry() {
        val b = box ?: return
        if (b.width == 0) return
        val bl = IntArray(2); b.getLocationOnScreen(bl)
        val isFull = bl[0] <= 0 && bl[1] <= 0 && b.width >= screenW() - 2 && b.height >= screenH() - 2
        var nc = Engine.cfg.copy(rx = bl[0], ry = bl[1], rw = b.width, rh = b.height, full = isFull)
        cross?.let { cr ->
            if (cr.width > 0) {
                val cl = IntArray(2); cr.getLocationOnScreen(cl)
                nc = nc.copy(tx = cl[0] + cr.width / 2, ty = cl[1] + cr.height / 2)
            }
        }
        if (nc != Engine.cfg) Engine.save(this, nc)
    }

    private fun exitEdit(save: Boolean, quiet: Boolean = false) {
        if (!Engine.editing && box == null) return
        if (save) saveGeometry()
        main.removeCallbacks(infoUpdater)
        listOf(box, cross, panel).forEach { v -> v?.let { try { wm.removeView(it) } catch (_: Exception) {} } }
        box = null; cross = null; panel = null; infoText = null; swatch = null; preview = null; modeBtn = null
        Engine.editing = false
        Engine.stateChanged(this)
        bubble?.invalidate()
        updateExclude()
        if (save && !quiet) Toast.makeText(this, "Zona guardada. Tocá la burbuja para empezar.", Toast.LENGTH_SHORT).show()
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
            style = Paint.Style.FILL; color = Color.rgb(255, 213, 79)
        }
        private var mode = 0
        private var dx = 0f; private var dy = 0f
        private var sx = 0; private var sy = 0; private var sw = 0; private var sh = 0

        override fun onDraw(canvas: Canvas) {
            val h = BORDER_PX / 2f
            canvas.drawRect(h, h, width - h, height - h, paint)
            // manija de tamaño
            val s = dp(26f).toFloat()
            val w = width.toFloat(); val hh = height.toFloat()
            val tri = Path().apply { moveTo(w, hh - s); lineTo(w, hh); lineTo(w - s, hh); close() }
            canvas.drawPath(tri, corner)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = boxLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y; sw = width; sh = height
                    val zone = max(dp(40f), minOf(width, height) / 4)
                    mode = if (e.x > width - zone && e.y > height - zone) 2 else 1
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (mode == 1) { p.x = sx + mx; p.y = sy + my }
                    else { p.width = max(dp(24f), sw + mx); p.height = max(dp(24f), sh + my) }
                    try { wm.updateViewLayout(this, p) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> saveGeometry()
            }
            return true
        }
    }

    /** Mira que marca dónde se va a tocar (modo punto fijo). */
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

    fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

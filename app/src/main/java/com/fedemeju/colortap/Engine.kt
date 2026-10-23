package com.fedemeju.colortap

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Configuración guardada. Coordenadas en píxeles absolutos de pantalla. */
data class Cfg(
    val rx: Int = -1, val ry: Int = -1, val rw: Int = 0, val rh: Int = 0, // sector a vigilar
    val tx: Int = -1, val ty: Int = -1,                                    // punto fijo (modo 1)
    val mode: Int = MODE_ON_COLOR,
    val color: Int = Color.rgb(229, 57, 53),                               // color objetivo
    val hueTol: Int = 18,        // tolerancia de tono (grados)
    val minSize: Int = 20,       // tamaño mínimo de la mancha (px de diámetro aprox.)
    val intervalMs: Int = 30,    // cada cuánto se revisa la pantalla
    val cooldownMs: Int = 80,    // espera mínima entre toques
    val repeat: Boolean = false, // modo punto fijo: seguir tocando mientras siga el color
    val multi: Boolean = true,   // si aparecen varios juntos, tocarlos a la vez
    val full: Boolean = false,   // vigilar toda la pantalla (se adapta al girar)
    val reactMs: Int = 260,      // modo carriles: cuánto antes esquivar (ms antes del choque)
    val mathMinMs: Int = 500,    // math quiz: demora mínima antes de tocar
    val mathMaxMs: Int = 1000,   // math quiz: demora máxima antes de tocar
    val zigLead: Int = 70,       // zigzag: anticipación del giro (ms, compensa la demora)
    val stackLead: Int = 70,     // stack: anticipación del toque (ms)
    val ninjaGap: Int = 110,     // ninja: ms entre toques seguidos
    val parkGap: Int = 90        // estacionar: ms mínimos entre toques
) {
    val hasRegion get() = full || (rw > 0 && rh > 0)
    val hasTarget get() = tx >= 0 && ty >= 0
    val ready get() = hasRegion && (mode != MODE_FIXED || hasTarget)

    companion object {
        const val MODE_ON_COLOR = 0   // tocar encima de cada mancha del color
        const val MODE_FIXED = 1      // tocar un punto fijo cuando aparece el color
        const val MODE_LANES = 2      // juego de 2 naves x 2 carriles: esquivar bloques
        const val MODE_MATH = 3       // leer una cuenta y tocar la opción correcta
        const val MODE_ZIGZAG = 4     // bola en camino zigzag: tocar para girar en cada curva
        val ZIG_YELLOW = Color.rgb(250, 226, 100)
        const val MODE_STACK = 5      // apilar bloques: tocar cuando el bloque queda alineado
        const val MODE_NINJA = 6      // cortar el bambú esquivando ramas (tipo Timberman)
        const val MODE_PARK = 7       // estacionar: llevar el auto al lugar libre con toques
        val PINK = Color.rgb(232, 67, 106)
    }
}

object Engine {
    @Volatile var cfg = Cfg()
    @Volatile var capturing = false
    @Volatile var active = false
    @Volatile var editing = false
    @Volatile var sampleRequest = false

    // Diagnóstico
    @Volatile var lastBlobs = 0
    @Volatile var tapCount = 0
    @Volatile var capW = 0
    @Volatile var capH = 0
    @Volatile var frames = 0
    @Volatile var black = false

    /** Zonas de pantalla ocupadas por nuestras propias ventanas (burbuja, panel). */
    @Volatile var exclude: List<Rect> = emptyList()

    /** Tamaño real de la pantalla (para convertir coordenadas de captura → toque). */
    @Volatile var screenW = 0
    @Volatile var screenH = 0

    // Vista previa (sólo en modo ajuste)
    @Volatile var previewRaw: IntArray? = null
    @Volatile var previewW = 0
    @Volatile var previewH = 0
    private var lastPreview = 0L

    private var lastCheck = 0L
    private var lastTap = 0L
    private var wasMatch = false
    private val thsv = FloatArray(3)
    private val main = Handler(Looper.getMainLooper())

    // memoria de toques recientes para no repetir el mismo círculo
    private val recentX = FloatArray(40)
    private val recentY = FloatArray(40)
    private val recentT = LongArray(40)
    private var recentI = 0
    private const val MEMORY_MS = 450L
    private val freshX = FloatArray(10)
    private val freshY = FloatArray(10)
    private val freshS = IntArray(10)

    // buffers reutilizables
    private var grid = BooleanArray(0)
    private var cols = IntArray(0)
    private var queue = IntArray(0)
    private var label = IntArray(0)

    // ---------- Persistencia ----------
    private const val P = "colortap"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val d = Cfg()
        cfg = Cfg(
            rx = p.getInt("rx", d.rx), ry = p.getInt("ry", d.ry),
            rw = p.getInt("rw", d.rw), rh = p.getInt("rh", d.rh),
            tx = p.getInt("tx", d.tx), ty = p.getInt("ty", d.ty),
            mode = p.getInt("mode", d.mode),
            color = p.getInt("color", d.color),
            hueTol = p.getInt("hueTol2", d.hueTol),
            minSize = p.getInt("minSize", d.minSize),
            intervalMs = p.getInt("intervalMs", d.intervalMs),
            cooldownMs = p.getInt("cooldownMs2", d.cooldownMs),
            repeat = p.getBoolean("repeat", d.repeat),
            multi = p.getBoolean("multi", d.multi),
            full = p.getBoolean("full", d.full),
            reactMs = p.getInt("reactMs", d.reactMs),
            mathMinMs = p.getInt("mathMin", d.mathMinMs),
            mathMaxMs = p.getInt("mathMax", d.mathMaxMs),
            zigLead = p.getInt("zigLeadMs", d.zigLead),
            stackLead = p.getInt("stackLead", d.stackLead),
            ninjaGap = p.getInt("ninjaGap", d.ninjaGap),
            parkGap = p.getInt("parkGap", d.parkGap)
        )
    }

    fun save(ctx: Context, c: Cfg) {
        cfg = c
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt("rx", c.rx).putInt("ry", c.ry).putInt("rw", c.rw).putInt("rh", c.rh)
            .putInt("tx", c.tx).putInt("ty", c.ty).putInt("mode", c.mode).putInt("color", c.color)
            .putInt("hueTol2", c.hueTol).putInt("minSize", c.minSize)
            .putInt("intervalMs", c.intervalMs).putInt("cooldownMs2", c.cooldownMs)
            .putBoolean("repeat", c.repeat).putBoolean("multi", c.multi).putBoolean("full", c.full).putInt("reactMs", c.reactMs).putInt("mathMin", c.mathMinMs).putInt("mathMax", c.mathMaxMs).putInt("zigLeadMs", c.zigLead).putInt("stackLead", c.stackLead).putInt("ninjaGap", c.ninjaGap).putInt("parkGap", c.parkGap).apply()
    }

    // ---------- Estado ----------
    fun setActive(ctx: Context, on: Boolean) {
        active = on && capturing && !editing && cfg.ready
        wasMatch = false
        recentT.fill(0)
        MathSolver.reset()
        pkPhi = -1f; pkTargetY = -1f; pkLastTap = 0L
        njPrevSig = ""; njLockUntil = 0L; njLastSig = ""
        stTV = Float.NaN; stPrevMV = Float.NaN; stV = 0f; stFired = false; stPrevT = 0L
        zzDir = -1; zzHalf = 0f; zzLock = 0L; zzPrevT = 0L; zzVx = 0f; zzV = 0.25f; zzPrevCur = 0f
        speedEma = 0f; prevObsT = 0L; sideLock.fill(0); sideLaneAtTap.fill(-1); sideLastLane.fill(-1); sideStable.fill(0); prevObs.fill(-1f)
        stateChanged(ctx)
    }

    fun stateChanged(ctx: Context) {
        main.post {
            ClickerService.instance?.refresh()
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, ToggleTileService::class.java))
            } catch (_: Exception) {}
        }
    }

    // ---------- Color ----------
    /** Devuelve true si el píxel r,g,b coincide con el color objetivo (thsv). */
    private fun matches(r: Int, g: Int, b: Int, hueTol: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val v = mx / 255f
        val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        val ts = thsv[1]; val tv = thsv[2]
        if (ts < 0.2f) return s < 0.25f && abs(v - tv) < 0.2f   // blanco/gris/negro
        if (s < max(0.25f, ts - 0.4f) || v < max(0.15f, tv - 0.45f) || v > tv + 0.45f) return false
        val d = (mx - mn).toFloat()
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0) h += 360f
        var dh = abs(h - thsv[0])
        if (dh > 180f) dh = 360f - dh
        return dh <= hueTol
    }

    // ---------- Detección (hilo de captura) ----------
    fun onFrame(ctx: Context, buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int) {
        if (!active && !editing) return
        val now = System.currentTimeMillis()
        val c = cfg
        if (now - lastCheck < c.intervalMs) return
        lastCheck = now
        frames++
        capW = w; capH = h
        if (!c.hasRegion) return

        // escala captura → pantalla (normalmente 1)
        val scX = if (screenW > 0) screenW.toFloat() / w else 1f
        val scY = if (screenH > 0) screenH.toFloat() / h else 1f

        val inset = if (editing) ClickerService.BORDER_PX + 2 else 0
        val x0: Int; val y0: Int; val x1: Int; val y1: Int
        if (c.full) {
            val ins = (inset / scX).toInt()
            x0 = ins; y0 = ins; x1 = w - ins; y1 = h - ins
        } else {
            x0 = max(0, ((c.rx + inset) / scX).toInt()); y0 = max(0, ((c.ry + inset) / scY).toInt())
            x1 = min(w, ((c.rx + c.rw - inset) / scX).toInt()); y1 = min(h, ((c.ry + c.rh - inset) / scY).toInt())
        }
        if (x1 - x0 < 2 || y1 - y0 < 2) return

        if (sampleRequest) {
            sampleRequest = false
            val col = sampleCenter(buf, x0, y0, x1, y1, rowStride, pixStride)
            save(ctx, c.copy(color = col))
            main.post { ClickerService.instance?.onColorSampled(col) }
            return
        }

        if (c.mode == Cfg.MODE_MATH) {
            MathSolver.onFrame(buf, w, h, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c, exclude)
            return
        }
        if (c.mode == Cfg.MODE_PARK) {
            parkFrame(buf, w, h, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c)
            return
        }
        if (c.mode == Cfg.MODE_NINJA) {
            ninjaFrame(buf, w, h, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c)
            return
        }
        if (c.mode == Cfg.MODE_STACK) {
            stackFrame(buf, w, h, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c)
            return
        }
        if (c.mode == Cfg.MODE_ZIGZAG) {
            Color.colorToHSV(c.color, thsv)
            zigzagFrame(buf, w, h, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c)
            return
        }
        if (c.mode == Cfg.MODE_LANES) {
            Color.colorToHSV(c.color, thsv)
            lanesFrame(buf, rowStride, pixStride, x0, y0, x1, y1, scX, scY, now, c)
            return
        }

        // Grilla de muestreo: ~1 muestra cada 'step' píxeles
        val area = (x1 - x0).toLong() * (y1 - y0)
        var step = max(2, sqrt(area / 60000.0).toInt() + 1)
        step = min(step, max(2, c.minSize / 3))
        val gw = (x1 - x0 + step - 1) / step
        val gh = (y1 - y0 + step - 1) / step
        val n = gw * gh
        if (grid.size < n) { grid = BooleanArray(n); cols = IntArray(n); queue = IntArray(n); label = IntArray(n) }

        Color.colorToHSV(c.color, thsv)
        val ex = exclude
        var sum = 0L
        var idx = 0
        for (gy in 0 until gh) {
            val y = y0 + gy * step
            val row = y * rowStride
            for (gx in 0 until gw) {
                val x = x0 + gx * step
                val i = row + x * pixStride
                val r = buf.get(i).toInt() and 0xff
                val g = buf.get(i + 1).toInt() and 0xff
                val b = buf.get(i + 2).toInt() and 0xff
                cols[idx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                sum += r + g + b
                var m = matches(r, g, b, c.hueTol)
                if (m && ex.isNotEmpty()) {
                    val sx = (x * scX).toInt(); val sy = (y * scY).toInt()
                    for (rc in ex) if (rc.contains(sx, sy)) { m = false; break }
                }
                grid[idx] = m
                label[idx] = 0
                idx++
            }
        }
        black = sum < n * 6L

        // Componentes conectados (manchas del color)
        val minCells = max(1, ((c.minSize.toFloat() / step) * (c.minSize.toFloat() / step) * 0.4f).toInt())
        var blobs = 0
        var bestX = 0f; var bestY = 0f; var bestSize = 0
        var nf = 0
        var lab = 0
        for (start in 0 until n) {
            if (!grid[start] || label[start] != 0) continue
            lab++
            var qh = 0; var qt = 0
            queue[qt++] = start; label[start] = lab
            var cnt = 0; var sxs = 0L; var sys = 0L
            while (qh < qt) {
                val p = queue[qh++]
                val px = p % gw; val py = p / gw
                cnt++; sxs += px; sys += py
                if (px > 0) { val q = p - 1; if (grid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (px < gw - 1) { val q = p + 1; if (grid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py > 0) { val q = p - gw; if (grid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py < gh - 1) { val q = p + gw; if (grid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
            }
            if (cnt < minCells) continue
            blobs++
            val cx = (x0 + (sxs.toFloat() / cnt) * step + step / 2f) * scX
            val cy = (y0 + (sys.toFloat() / cnt) * step + step / 2f) * scY
            if (cnt > bestSize) { bestSize = cnt; bestX = cx; bestY = cy }
            if (!recentlyTapped(cx, cy, sqrt(cnt.toFloat()) * step * scX, now)) {
                // guardar los más grandes (hasta 10)
                if (nf < freshX.size) { freshX[nf] = cx; freshY[nf] = cy; freshS[nf] = cnt; nf++ }
                else {
                    var mi = 0
                    for (k in 1 until nf) if (freshS[k] < freshS[mi]) mi = k
                    if (cnt > freshS[mi]) { freshX[mi] = cx; freshY[mi] = cy; freshS[mi] = cnt }
                }
            }
        }
        lastBlobs = blobs

        if (editing && now - lastPreview > 300) {
            lastPreview = now
            buildPreview(gw, gh, n)
        }

        if (!active) return
        // No mandar un toque nuevo mientras el anterior sigue en curso (se cancelaría)
        if (busy && now - busySince < TAP_MS + 250) return
        if (now - lastTap < c.cooldownMs) return

        if (c.mode == Cfg.MODE_ON_COLOR) {
            if (nf > 0) {
                lastTap = now
                if (c.multi) {
                    val xs = FloatArray(nf); val ys = FloatArray(nf)
                    for (k in 0 until nf) { xs[k] = freshX[k]; ys[k] = freshY[k]; remember(xs[k], ys[k], now) }
                    tapCount += nf
                    fire { ClickerService.instance?.tapMany(xs, ys) }
                } else {
                    var bi = 0
                    for (k in 1 until nf) if (freshS[k] > freshS[bi]) bi = k
                    remember(freshX[bi], freshY[bi], now)
                    tapCount++
                    fire { ClickerService.instance?.tap(freshX[bi], freshY[bi]) }
                }
            }
        } else {
            val match = blobs > 0
            if (match && (c.repeat || !wasMatch)) {
                lastTap = now
                tapCount++
                fire { ClickerService.instance?.tap(c.tx.toFloat(), c.ty.toFloat()) }
            }
            wasMatch = match
        }
    }

    private fun recentlyTapped(x: Float, y: Float, radius: Float, now: Long): Boolean {
        val r = max(radius, 30f)
        for (i in recentT.indices) {
            if (now - recentT[i] > MEMORY_MS) continue
            val dx = recentX[i] - x; val dy = recentY[i] - y
            if (dx * dx + dy * dy < r * r) return true
        }
        return false
    }

    const val TAP_MS = 30L
    @Volatile private var busy = false
    @Volatile private var busySince = 0L

    private inline fun fire(f: () -> Unit) {
        busy = true; busySince = System.currentTimeMillis()
        f()
    }

    /** Llamado cuando Android termina (o cancela) un toque. */
    fun onGestureDone(xs: FloatArray, ys: FloatArray, completed: Boolean) {
        busy = false
        if (!completed) {
            // no se registró: olvidar esos blancos para volver a tocarlos ya
            synchronized(recentT) {
                for (k in xs.indices) for (i in recentT.indices) {
                    if (recentX[i] == xs[k] && recentY[i] == ys[k]) recentT[i] = 0
                }
            }
            tapCount -= xs.size
        }
    }

    private fun remember(x: Float, y: Float, now: Long) {
        recentX[recentI] = x; recentY[recentI] = y; recentT[recentI] = now
        recentI = (recentI + 1) % recentT.size
    }

    // =====================================================================
    // MODO CARRILES: 2 naves (izq/der), 2 carriles cada una, bloques que bajan.
    // La zona (marco) = área de juego; se divide en 4 carriles iguales.
    // =====================================================================
    @Volatile var laneInfo = ""
    private var ltype = ByteArray(0)       // 0 nada, 1 obstáculo, 2 nave
    private var speedEma = 0f              // velocidad de los bloques (px de captura por ms)
    private val prevObs = FloatArray(4) { -1f }
    private var prevObsT = 0L
    private val sideLock = LongArray(2)
    private val sideLaneAtTap = IntArray(2) { -1 }
    private val sideLastLane = IntArray(2) { -1 }
    private val sideStable = LongArray(2)

    private fun hueOf(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0) h += 360f
        return h
    }

    /** Naves: celeste/verde agua o naranja, brillantes y saturadas. */
    private fun isShip(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx < 150) return false
        if ((mx - mn).toFloat() / mx < 0.42f) return false
        val h = hueOf(r, g, b, mx, mn)
        return (h in 145f..205f) || (h in 10f..37f)
    }

    /** Bloques: color intenso. Descarta resplandores/brillos apagados del fondo. */
    private fun strongColor(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        return mx >= 128 && (mx - mn) * 100 >= mx * 57
    }

    private fun lanesFrame(
        buf: ByteBuffer, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        scX: Float, scY: Float, now: Long, c: Cfg
    ) {
        val step = max(3, (x1 - x0) / 200)
        val gw = (x1 - x0) / step
        val gh = (y1 - y0) / step
        if (gw < 8 || gh < 8) return
        val n = gw * gh
        if (ltype.size < n) ltype = ByteArray(n)
        if (cols.size < n) cols = IntArray(n)
        val ex = exclude
        var sum = 0L

        // 1) clasificar cada muestra
        var idx = 0
        for (gy in 0 until gh) {
            val y = y0 + gy * step
            val row = y * rs
            for (gx in 0 until gw) {
                val x = x0 + gx * step
                val i = row + x * ps
                val r = buf.get(i).toInt() and 0xff
                val g = buf.get(i + 1).toInt() and 0xff
                val b = buf.get(i + 2).toInt() and 0xff
                cols[idx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                sum += r + g + b
                var t: Byte = when {
                    isShip(r, g, b) -> 2
                    matches(r, g, b, c.hueTol) && strongColor(r, g, b) -> 1
                    else -> 0
                }
                if (t != 0.toByte() && ex.isNotEmpty()) {
                    val sx = (x * scX).toInt(); val sy = (y * scY).toInt()
                    for (rc in ex) if (rc.contains(sx, sy)) { t = 0; break }
                }
                ltype[idx] = t
                idx++
            }
        }
        black = sum < n * 6L

        // columnas de cada carril (con margen para no tocar el carril vecino)
        val laneCols = gw / 4f
        fun laneRange(l: Int): IntRange {
            val a = (l * laneCols + laneCols * 0.12f).toInt()
            val b = ((l + 1) * laneCols - laneCols * 0.12f).toInt() - 1
            return a..max(a, b)
        }
        val laneSamples = IntArray(4) { laneRange(it).count() }

        // fila con obstáculo en carril l: al menos 30% del ancho del carril
        fun obsRow(l: Int, gy: Int): Boolean {
            var k = 0
            val base = gy * gw
            for (gx in laneRange(l)) if (ltype[base + gx] == 1.toByte()) k++
            return k * 10 >= laneSamples[l] * 3
        }

        // 2) naves: buscar en la mitad inferior de la zona
        val shipCnt = IntArray(4); val shipTop = IntArray(4) { Int.MAX_VALUE }; val shipBot = IntArray(4) { -1 }
        for (gy in (gh * 45 / 100) until gh) {
            val base = gy * gw
            for (l in 0 until 4) for (gx in laneRange(l)) {
                if (ltype[base + gx] == 2.toByte()) {
                    shipCnt[l]++
                    if (gy < shipTop[l]) shipTop[l] = gy
                    if (gy > shipBot[l]) shipBot[l] = gy
                }
            }
        }

        // 3) carril de cada nave (sólo si está claro: el doble que en el otro carril)
        val sideShipLane = IntArray(2) { -1 }
        for (sd in 0..1) {
            val a = sd * 2; val b = a + 1
            val best = if (shipCnt[a] >= shipCnt[b]) a else b
            val other = if (best == a) b else a
            if (shipCnt[best] >= 4 && shipCnt[best] >= shipCnt[other] * 2) sideShipLane[sd] = best
        }
        if (sideShipLane[0] < 0 && sideShipLane[1] < 0) {
            sideStable.fill(0)
        }

        // Distancia (en celdas) desde la nave hasta el bloque más cercano en el carril l.
        // 0 = hay un bloque al lado de la nave. Los bloques que ya pasaron se ignoran.
        val INF = Int.MAX_VALUE
        val maxRun = max(6, gh * 12 / 100)   // un bloque mide ~5% de alto; más de 12% no es un bloque
        fun distIn(l: Int, top: Int, bot: Int): Int {
            var gy = min(gh - 1, bot)
            while (gy >= 1) {
                if (obsRow(l, gy) && obsRow(l, gy - 1)) {
                    // medir el alto de la mancha
                    var t = gy - 1
                    while (t > 0 && obsRow(l, t - 1)) t--
                    if (gy - t + 1 <= maxRun) return max(0, top - gy)
                    gy = t - 1
                    continue
                }
                gy--
            }
            return INF
        }

        // 4) velocidad: seguir el bloque más cercano de cada carril
        val shipRef = IntArray(2) { sd -> if (sideShipLane[sd] >= 0) sideShipLane[sd] else -1 }
        val obsNow = FloatArray(4) { -1f }
        for (l in 0 until 4) {
            val sl = shipRef[l / 2]
            val top = if (sl >= 0) shipTop[sl] else gh - 1
            val d = distIn(l, top, top)
            if (d != INF && d > 0) obsNow[l] = (top - d).toFloat()
        }
        if (prevObsT > 0) {
            val dt = (now - prevObsT).toFloat()
            if (dt in 5f..200f) {
                for (l in 0 until 4) {
                    if (obsNow[l] < 0 || prevObs[l] < 0) continue
                    val dy = (obsNow[l] - prevObs[l]) * step
                    if (dy > 0 && dy < (y1 - y0) * 0.08f) {
                        val v = min(dy / dt, 6f)
                        speedEma = if (speedEma == 0f) v else speedEma * 0.85f + v * 0.15f
                    }
                }
            }
        }
        for (l in 0 until 4) prevObs[l] = obsNow[l]
        prevObsT = now

        // 5) decidir
        val tx = FloatArray(2); val ty = FloatArray(2); var nt = 0
        val tapSides = IntArray(2)
        val info = StringBuilder()
        for (sd in 0..1) {
            val L = sideShipLane[sd]
            info.append(if (sd == 0) "Izq: " else "  Der: ")
            if (L < 0) { info.append("?"); sideStable[sd] = 0; continue }
            info.append("carril ${L % 2 + 1}")
            // la nave tiene que estar quieta en el mismo carril un momento (no en plena animación)
            if (L != sideLastLane[sd]) { sideLastLane[sd] = L; sideStable[sd] = now }
            if (sideLaneAtTap[sd] >= 0) {
                if (L != sideLaneAtTap[sd]) { sideLaneAtTap[sd] = -1; sideLock[sd] = 0 }
                else if (now < sideLock[sd]) continue
                else sideLaneAtTap[sd] = -1   // el toque no hizo efecto: permitir reintento
            }
            if (now - sideStable[sd] < 70) continue

            val top = shipTop[L]; val bot = shipBot[L]
            val shipH = max(3, bot - top + 1)
            val reach = max(shipH * 1.5f, speedEma * c.reactMs / step)
            val dCur = distIn(L, top, bot)
            if (dCur == INF || dCur > reach) continue
            val O = if (L % 2 == 0) L + 1 else L - 1
            val dOther = distIn(O, top, bot)
            // sólo cambiar si el otro carril está claramente más libre
            if (dOther != INF && dOther <= dCur + shipH) { info.append(" (bloqueado)"); continue }
            tx[nt] = (x0 + (x1 - x0) * (if (sd == 0) 0.25f else 0.75f)) * scX
            ty[nt] = (y1 - (y1 - y0) * 0.06f) * scY
            tapSides[nt] = sd
            nt++
        }
        info.append(String.format("\nVelocidad: %.2f", speedEma))
        laneInfo = info.toString()
        lastBlobs = nt

        if (editing && now - lastPreview > 300) {
            lastPreview = now
            val raw = cols.copyOf(n); val shown = IntArray(n)
            for (i in 0 until n) {
                val gx = i % gw
                shown[i] = when {
                    ltype[i] == 1.toByte() -> Color.rgb(0, 255, 90)
                    ltype[i] == 2.toByte() -> Color.rgb(40, 140, 255)
                    gx == gw / 4 || gx == gw * 3 / 4 -> Color.rgb(90, 90, 90)
                    gx == gw / 2 -> Color.rgb(200, 200, 200)
                    else -> { val cc = raw[i]; Color.rgb((Color.red(cc) * 0.5f).toInt(), (Color.green(cc) * 0.5f).toInt(), (Color.blue(cc) * 0.5f).toInt()) }
                }
            }
            previewRaw = raw; previewW = gw; previewH = gh
            val bmp = try { Bitmap.createBitmap(shown, gw, gh, Bitmap.Config.ARGB_8888) } catch (_: Exception) { null }
            if (bmp != null) main.post { ClickerService.instance?.onPreview(bmp) }
        }

        if (!active || nt == 0) return
        if (busy && now - busySince < TAP_MS + 250) return
        for (k in 0 until nt) {
            val sd = tapSides[k]
            sideLock[sd] = now + 350
            sideLaneAtTap[sd] = sideShipLane[sd]
        }
        lastTap = now
        tapCount += nt
        val xs = tx.copyOf(nt); val ys = ty.copyOf(nt)
        fire { ClickerService.instance?.tapMany(xs, ys) }
    }

    // =====================================================================
    // MODO ZIGZAG: la bola avanza en diagonal (arriba-izq / arriba-der).
    // Se mide cuánto camino queda delante en cada diagonal; al llegar a la
    // curva (queda poco en la dirección actual y se abre la otra) se toca.
    // =====================================================================
    @Volatile var zigInfo = ""
    private var zzDir = -1            // 0 = arriba-izquierda, 1 = arriba-derecha, -1 = sin saber
    private var zzHalf = 0f
    private var zzPrevT = 0L
    @Volatile var zzHud = ""
    @Volatile var zzLastTapInfo = ""
    private var zzPrevX = 0f
    private var zzVx = 0f
    private var zzV = 0.25f
    private var zzPrevCur = 0f
    private var zzPrevCurT = 0L
    private const val ZZ_ANG_R = 22
    private const val ZZ_ANG_L = 36
    private var zzLock = 0L
    private var zgrid = ByteArray(0)

    /** Superficie superior del camino: mismo tono y bien brillante (las paredes son más oscuras). */
    private fun isPathTop(r: Int, g: Int, b: Int, tol: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx == 0) return false
        val v = mx / 255f; val s = (mx - mn).toFloat() / mx
        if (v < thsv[2] - 0.17f || s < 0.3f) return false
        var dh = abs(hueOf(r, g, b, mx, mn) - thsv[0]); if (dh > 180f) dh = 360f - dh
        return dh <= tol
    }

    private fun isBall(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        return mx < 80 && mx - mn < 16
    }

    private fun zigzagFrame(
        buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        scX: Float, scY: Float, now: Long, c: Cfg
    ) {
        val step = max(3, (x1 - x0) / 180)
        val gw = (x1 - x0) / step; val gh = (y1 - y0) / step
        if (gw < 8 || gh < 8) return
        val n = gw * gh
        if (zgrid.size < n) zgrid = ByteArray(n)
        if (cols.size < n) cols = IntArray(n)
        if (queue.size < n) { queue = IntArray(n); label = IntArray(n) }
        var sum = 0L
        var idx = 0
        for (gy in 0 until gh) {
            val row = (y0 + gy * step) * rs
            for (gx in 0 until gw) {
                val i = row + (x0 + gx * step) * ps
                val r = buf.get(i).toInt() and 0xff; val g = buf.get(i + 1).toInt() and 0xff; val b = buf.get(i + 2).toInt() and 0xff
                cols[idx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                sum += r + g + b
                var tz: Byte = when { isBall(r, g, b) -> 2; isPathTop(r, g, b, c.hueTol) -> 1; else -> 0 }
                if (tz == 2.toByte() && exclude.isNotEmpty()) {
                    val ex2 = ((x0 + gx * step) * scX).toInt(); val ey2 = ((y0 + gy * step) * scY).toInt()
                    for (rc in exclude) if (rc.contains(ex2, ey2)) { tz = 0; break }
                }
                zgrid[idx] = tz
                label[idx] = 0
                idx++
            }
        }
        black = sum < n * 6L

        // bola = mancha oscura más grande (con tamaño razonable)
        var bestCnt = 0; var bx = 0f; var by = 0f
        var lab = 0
        for (st in 0 until n) {
            if (zgrid[st] != 2.toByte() || label[st] != 0) continue
            lab++
            var qh = 0; var qt = 0
            queue[qt++] = st; label[st] = lab
            var cnt = 0; var sx = 0L; var sy = 0L
            while (qh < qt) {
                val p = queue[qh++]; val px = p % gw; val py = p / gw
                cnt++; sx += px; sy += py
                if (px > 0) { val q = p - 1; if (zgrid[q] == 2.toByte() && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (px < gw - 1) { val q = p + 1; if (zgrid[q] == 2.toByte() && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py > 0) { val q = p - gw; if (zgrid[q] == 2.toByte() && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py < gh - 1) { val q = p + gw; if (zgrid[q] == 2.toByte() && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
            }
            if (cnt > bestCnt && cnt < n / 150) { bestCnt = cnt; bx = sx.toFloat() / cnt; by = sy.toFloat() / cnt }
        }

        val ballOk = bestCnt >= 4
        val ballX = x0 + bx * step + step / 2f
        val ballY = y0 + by * step + step / 2f
        val rad = sqrt(bestCnt / 3.14f) * step

        fun isTop(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= w || y >= h) return false
            val i = y * rs + x * ps
            return isPathTop(buf.get(i).toInt() and 0xff, buf.get(i + 1).toInt() and 0xff, buf.get(i + 2).toInt() and 0xff, c.hueTol)
        }
        /** Largo de camino desde (ox,oy) en dirección (dx,dy). Salta la bola/sombra al principio. */
        fun ray(ox: Float, oy: Float, dx: Float, dy: Float, start: Float, maxD: Float = 700f): Float {
            var d = start; var miss = 0; var last = 0f; var hit = false
            while (d < maxD) {
                val x = (ox + dx * d).toInt(); val y = (oy + dy * d).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) break
                if (isTop(x, y)) { miss = 0; last = d; hit = true }
                else if (!hit && d < start + rad * 2f) { d += 2f; continue }
                else if (++miss >= 2) break
                d += 2f
            }
            return last
        }

        var tapNow = false
        if (ballOk) {
            // Dirección: la cámara sigue a la bola en vertical, así que en pantalla
            // la bola sólo se mueve a la derecha (↗) o a la izquierda (↖).
            if (zzPrevT > 0) {
                val dt = (now - zzPrevT).toFloat()
                if (dt in 4f..250f) {
                    val vx = ((ballX - zzPrevX) / dt).coerceIn(-3f, 3f)
                    zzVx = zzVx * 0.6f + vx * 0.4f
                }
            }
            zzPrevX = ballX; zzPrevT = now
            if (now >= zzLock) {
                if (zzVx > 0.03f) zzDir = 1 else if (zzVx < -0.03f) zzDir = 0
            }

            var cInfo = ""
            if (zzDir >= 0 && now >= zzLock) {
                // ángulos del camino en pantalla (cámara inclinada): ↗ ~22°, ↖ ~36°
                val aD = Math.toRadians((if (zzDir == 1) ZZ_ANG_R else ZZ_ANG_L).toDouble())
                val aO = Math.toRadians((if (zzDir == 1) ZZ_ANG_L else ZZ_ANG_R).toDouble())
                val sD = if (zzDir == 1) 1f else -1f
                val dX = sD * Math.cos(aD).toFloat(); val dY = -Math.sin(aD).toFloat()
                val oX = -sD * Math.cos(aO).toFloat(); val oY = -Math.sin(aO).toFloat()
                val st = rad * 1.2f
                val cur = ray(ballX, ballY, dX, dY, rad * 1.1f)
                // velocidad con la que se acerca el final del tramo
                if (zzPrevCur > 0f && zzPrevCurT > 0) {
                    val dt = (now - zzPrevCurT).toFloat()
                    val dv = (zzPrevCur - cur) / max(1f, dt)
                    if (dv > 0f && dv < 2f) zzV = zzV * 0.7f + dv * 0.3f
                }
                zzPrevCur = cur; zzPrevCurT = now
                // ancho del lado actual, un poco detrás de la bola
                val s0 = ray(ballX - dX * rad * 2.5f, ballY - dY * rad * 2.5f, oX, oY, st)
                val thr = max(s0 * 1.6f, s0 + rad * 2f)
                // buscar adelante la entrada del tramo nuevo (donde la otra diagonal se abre)
                val tMax = min(cur + rad, 650f)
                var t1 = Float.NaN; var t2 = Float.NaN
                var t = -rad
                while (t <= tMax) {
                    val open = ray(ballX + dX * t, ballY + dY * t, oX, oY, st) >= thr
                    if (open) { if (t1.isNaN()) t1 = t; t2 = t }
                    else if (!t1.isNaN()) break
                    t += 3f
                }
                if (!t1.isNaN() && s0 > 0f) {
                    val tc = (t1 + t2) / 2f; val wd = t2 - t1
                    cInfo = String.format("\nPróxima curva: %d px (ancho %d)", tc.toInt(), wd.toInt())
                    zzHud = String.format("%s curva %d px · ancho %d · vel %.2f", if (zzDir == 1) "↗" else "↖", tc.toInt(), wd.toInt(), zzV)
                    if (wd >= rad * 2.5f && tc <= wd * 0.5f * 0.35f + zzV * c.zigLead) {
                        zzLastTapInfo = String.format("ÚLTIMO TOQUE: curva a %d px (límite %d)", tc.toInt(), (wd * 0.5f * 0.35f + zzV * c.zigLead).toInt())
                        tapNow = true
                        zzDir = 1 - zzDir
                        zzLock = now + 130
                        zzVx = 0f; zzPrevCur = 0f
                    }
                }
            }
            if (cInfo.isEmpty()) zzHud = (when (zzDir) { 0 -> "↖"; 1 -> "↗"; else -> "?" }) + " sin curva a la vista"
            zigInfo = String.format(
                "Bola: sí · va hacia %s",
                when (zzDir) { 0 -> "↖"; 1 -> "↗"; else -> "? (tocá vos para empezar)" }
            ) + cInfo
        } else {
            zigInfo = "No encuentro la bola dentro del marco"
            zzHud = "sin bola"
        }
        lastBlobs = if (ballOk) 1 else 0

        if (editing && now - lastPreview > 300) {
            lastPreview = now
            val raw = cols.copyOf(n); val shown = IntArray(n)
            for (i in 0 until n) shown[i] = when (zgrid[i]) {
                1.toByte() -> Color.rgb(0, 255, 90)
                2.toByte() -> Color.rgb(40, 140, 255)
                else -> { val cc = raw[i]; Color.rgb((Color.red(cc) * 0.5f).toInt(), (Color.green(cc) * 0.5f).toInt(), (Color.blue(cc) * 0.5f).toInt()) }
            }
            previewRaw = raw; previewW = gw; previewH = gh
            val bmp = try { Bitmap.createBitmap(shown, gw, gh, Bitmap.Config.ARGB_8888) } catch (_: Exception) { null }
            if (bmp != null) main.post { ClickerService.instance?.onPreview(bmp) }
        }

        if (!active || !tapNow) return
        lastTap = now
        tapCount++
        val tx = w * 0.5f * scX; val ty = h * 0.86f * scY
        fire { ClickerService.instance?.tap(tx, ty) }
    }

    // =====================================================================
    // MODO STACK: un bloque se desliza en diagonal sobre la torre.
    // La cámara sólo se mueve en vertical, así que se compara en horizontal
    // la esquina de atrás (punto más alto) del bloque que se mueve con la
    // de la torre, y se toca cuando van a coincidir.
    // =====================================================================
    private var stTV = Float.NaN       // x de la esquina de la torre
    private var stPrevMV = Float.NaN
    private var stV = 0f               // velocidad horizontal del bloque (px/ms)
    private var stFired = false
    private var stPrevT = 0L
    @Volatile private var stLastJudge = "–"
    private var sgrid = BooleanArray(0)

    /** Bloques: cualquier color saturado que no sea el violeta/azul del fondo. */
    private fun isBlock(r: Int, g: Int, b: Int): Boolean {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx < 70 || (mx - mn) * 255 < 70 * mx) return false
        val hh = hueOf(r, g, b, mx, mn)
        return hh < 210f || hh > 300f
    }

    // fondo por fila (el degradé no se mueve): se aprende de los bordes cuando ambos coinciden
    private var bgR = FloatArray(0); private var bgG = FloatArray(0); private var bgB = FloatArray(0)
    private var bgOk = BooleanArray(0)
    private var stAdj = 0f              // corrección automática de la anticipación (ms)
    private var stFiredAt = 0L
    private var stFiredDir = 0
    private var stFiredTV = 0f
    private var stJudged = true

    private fun stackFrame(
        buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        scX: Float, scY: Float, now: Long, c: Cfg
    ) {
        val step = max(4, (x1 - x0) / 270)
        val gw = (x1 - x0) / step; val gh = (y1 - y0) / step
        if (gw < 8 || gh < 8) return
        val n = gw * gh
        if (sgrid.size < n) sgrid = BooleanArray(n)
        if (cols.size < n) cols = IntArray(n)
        if (queue.size < n) { queue = IntArray(n); label = IntArray(n) }
        if (bgR.size != h) { bgR = FloatArray(h); bgG = FloatArray(h); bgB = FloatArray(h); bgOk = BooleanArray(h) }
        val ex = exclude
        val skipTop = gh * 12 / 100
        var sum = 0L
        var idx = 0
        val tmp = IntArray(gw)
        for (gy in 0 until gh) {
            val y = y0 + gy * step
            val row = y * rs
            // bordes izquierdo y derecho de la pantalla
            var lr = 0; var lg = 0; var lb = 0; var rr = 0; var rg = 0; var rb = 0
            for (k in 1..6) {
                val il = row + k * ps; val ir = row + (w - 1 - k) * ps
                lr += buf.get(il).toInt() and 0xff; lg += buf.get(il + 1).toInt() and 0xff; lb += buf.get(il + 2).toInt() and 0xff
                rr += buf.get(ir).toInt() and 0xff; rg += buf.get(ir + 1).toInt() and 0xff; rb += buf.get(ir + 2).toInt() and 0xff
            }
            if (abs(lr - rr) + abs(lg - rg) + abs(lb - rb) < 20 * 6) {
                val mr = (lr + rr) / 12f; val mg = (lg + rg) / 12f; val mb = (lb + rb) / 12f
                if (bgOk[y]) { bgR[y] = bgR[y] * 0.8f + mr * 0.2f; bgG[y] = bgG[y] * 0.8f + mg * 0.2f; bgB[y] = bgB[y] * 0.8f + mb * 0.2f }
                else { bgR[y] = mr; bgG[y] = mg; bgB[y] = mb; bgOk[y] = true }
            }
            // primera vez sin bordes coincidentes: mediana de la fila
            if (!bgOk[y]) {
                for (ch in 0..2) {
                    var k = 0
                    for (gx in 0 until gw step 4) { tmp[k++] = buf.get(row + (x0 + gx * step) * ps + ch).toInt() and 0xff }
                    java.util.Arrays.sort(tmp, 0, k)
                    val m = tmp[k / 2].toFloat()
                    when (ch) { 0 -> bgR[y] = m; 1 -> bgG[y] = m; else -> bgB[y] = m }
                }
                bgOk[y] = true
            }
            val br = bgR[y]; val bgg = bgG[y]; val bb = bgB[y]
            for (gx in 0 until gw) {
                val x = x0 + gx * step
                val i = row + x * ps
                val r = buf.get(i).toInt() and 0xff; val g = buf.get(i + 1).toInt() and 0xff; val b = buf.get(i + 2).toInt() and 0xff
                cols[idx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                sum += r + g + b
                var m = false
                if (gy >= skipTop) {
                    val dist = abs(r - br) + abs(g - bgg) + abs(b - bb)
                    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                    m = dist > 36f && (mx - mn > 45 || mx < 90)   // color de bloque (no texto blanco)
                }
                if (m && ex.isNotEmpty()) {
                    val sx = (x * scX).toInt(); val sy = (y * scY).toInt()
                    for (rc in ex) if (rc.contains(sx, sy)) { m = false; break }
                }
                sgrid[idx] = m
                label[idx] = 0
                idx++
            }
        }
        black = sum < n * 6L

        // manchas: [área, arriba, abajo, x de la esquina de atrás * 16]
        val bigCells = max(30, n * 9 / 1000)
        val smallCells = max(4, n * 8 / 10000)
        val blobs = ArrayList<IntArray>()
        var lab = 0
        for (stt in 0 until n) {
            if (!sgrid[stt] || label[stt] != 0) continue
            lab++
            var qh = 0; var qt = 0
            queue[qt++] = stt; label[stt] = lab
            var cnt = 0; var top = Int.MAX_VALUE; var bot = 0; var sxAll = 0L
            while (qh < qt) {
                val p = queue[qh++]; val px = p % gw; val py = p / gw
                cnt++; sxAll += px
                if (py < top) top = py; if (py > bot) bot = py
                if (px > 0) { val q = p - 1; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (px < gw - 1) { val q = p + 1; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py > 0) { val q = p - gw; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py < gh - 1) { val q = p + gw; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
            }
            if (cnt < smallCells) continue
            var sx = 0L; var k = 0
            for (gy in top..min(gh - 1, top + 1)) for (gx in 0 until gw) {
                if (label[gy * gw + gx] == lab) { sx += gx; k++ }
            }
            val topx = x0 + (sx.toFloat() / max(1, k)) * step + step / 2f
            val cx = x0 + (sxAll.toFloat() / cnt) * step
            blobs.add(intArrayOf(cnt, top, bot, (topx * 16).toInt(), (cx * 16).toInt()))
        }
        val big = blobs.filter { it[0] >= bigCells }
        if (big.isEmpty()) { zzHud = "sin bloques a la vista"; zigInfo = zzHud; lastBlobs = 0; stackPreview(gw, gh, n, now); return }
        val tower = big.maxByOrNull { it[2] }!!     // la torre llega más abajo
        val tTop = tower[1]; val tX = tower[3] / 16f
        // bloque que se mueve: la mancha grande por encima de la torre MÁS CERCANA a ella
        // (así se ignoran fotos de perfil, botones y textos de la parte de arriba)
        var mTop = -1; var mX = 0f; var mFound = false
        for (bb in big) if (bb !== tower && bb[1] < tTop && bb[2] >= tTop - gh / 4 && bb[1] > mTop) { mTop = bb[1]; mX = bb[3] / 16f; mFound = true }

        // autocorrección: ¿de qué lado cayó el pedazo cortado después del último toque?
        if (!stJudged && now - stFiredAt in 60L..450L) {
            val wz = (x1 - x0).toFloat()
            for (bb in blobs) {
                if (bb[0] >= bigCells) continue
                if (abs(bb[1] - tTop) > gh * 15 / 100) continue
                val dxs = bb[4] / 16f - stFiredTV
                if (abs(dxs) > wz * 0.25f || abs(dxs) < step * 2) continue
                val late = (dxs > 0) == (stFiredDir > 0)
                stAdj = (stAdj + if (late) 8f else -8f).coerceIn(-80f, 80f)
                stLastJudge = if (late) "tarde → adelanto" else "temprano → atraso"
                stJudged = true
                break
            }
        }
        if (!stJudged && now - stFiredAt > 450L) { stJudged = true; stLastJudge = "¡perfecto!" }

        val jump = (x1 - x0) * 0.11f
        var mv = Float.NaN
        if (mFound) {
            if (stPrevMV.isNaN() || abs(mX - stPrevMV) > jump) { stFired = false; stV = 0f; stPrevMV = Float.NaN }
            stTV = tX
            mv = mX
        } else if (!stTV.isNaN() && !stPrevMV.isNaN()) {
            if (abs(tX - stPrevMV) <= jump) mv = tX else stPrevMV = Float.NaN
        }
        if (!mv.isNaN()) {
            if (!stPrevMV.isNaN() && stPrevT > 0) {
                val dt = (now - stPrevT).toFloat()
                if (dt in 1f..120f) stV = stV * 0.6f + ((mv - stPrevMV) / dt) * 0.4f
            }
            stPrevMV = mv; stPrevT = now
        }
        lastBlobs = if (mFound || !mv.isNaN()) 1 else 0

        var delay = -1L
        val lead = c.stackLead + stAdj
        if (!mv.isNaN() && !stTV.isNaN()) {
            val dx = mv - stTV
            zzHud = String.format("dif %d px · vel %.2f · ajuste %+d ms (%s)", dx.toInt(), stV, stAdj.toInt(), stLastJudge)
            if (!stFired && dx * stV < 0f && abs(stV) > 0.02f) {
                // en cuántos ms se alinea, menos la anticipación → programar el toque justo
                val ta = abs(dx) / abs(stV) - lead
                if (ta <= 45f) {
                    delay = max(0f, ta).toLong()
                    stFired = true
                    stFiredAt = now + delay; stFiredDir = if (stV > 0) 1 else -1; stFiredTV = stTV; stJudged = false
                    zzLastTapInfo = String.format("ÚLTIMO TOQUE: dif %d px, en %d ms", dx.toInt(), delay)
                }
            }
        } else {
            zzHud = if (stTV.isNaN()) "esperando ver la torre y el bloque" else "buscando el bloque…"
        }
        zigInfo = zzHud
        stackPreview(gw, gh, n, now)

        if (!active || delay < 0) return
        lastTap = now
        tapCount++
        val tx = w * 0.5f * scX; val ty = h * 0.82f * scY
        main.postDelayed({
            if (active && cfg.mode == Cfg.MODE_STACK) ClickerService.instance?.tap(tx, ty)
        }, delay)
    }

    private fun stackPreview(gw: Int, gh: Int, n: Int, now: Long) {
        if (!editing || now - lastPreview <= 300) return
        lastPreview = now
        val raw = cols.copyOf(n); val shown = IntArray(n)
        for (i in 0 until n) shown[i] = if (sgrid[i]) Color.rgb(0, 255, 90) else {
            val cc = raw[i]; Color.rgb((Color.red(cc) * 0.5f).toInt(), (Color.green(cc) * 0.5f).toInt(), (Color.blue(cc) * 0.5f).toInt())
        }
        previewRaw = raw; previewW = gw; previewH = gh
        val bmp = try { Bitmap.createBitmap(shown, gw, gh, Bitmap.Config.ARGB_8888) } catch (_: Exception) { null }
        if (bmp != null) main.post { ClickerService.instance?.onPreview(bmp) }
    }

    // =====================================================================
    // MODO NINJA (tipo Timberman): importa la rama justo encima del ninja.
    // Se toca del lado contrario a esa rama (o del mismo lado si no hay).
    // Sólo se decide con el árbol quieto (no en plena animación).
    // =====================================================================
    private var njPrevSig = ""
    private var njLastSig = ""
    private var njLockUntil = 0L
    private var njSeg = 0f

    private fun isLeaf(r: Int, g: Int, b: Int) = b < 45 && g > 85 && g >= r + 8

    private fun ninjaFrame(
        buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        scX: Float, scY: Float, now: Long, c: Cfg
    ) {
        val sx = 3; val sy = 2
        val gw = (x1 - x0) / sx; val gh = (y1 - y0) / sy
        if (gw < 20 || gh < 40) return
        val n = gw * gh
        if (sgrid.size < n) sgrid = BooleanArray(n)
        val dark = if (zgrid.size >= n) zgrid else ByteArray(n).also { zgrid = it }
        var sum = 0L
        for (gy in 0 until gh) {
            val row = (y0 + gy * sy) * rs
            val base = gy * gw
            for (gx in 0 until gw) {
                val i = row + (x0 + gx * sx) * ps
                val r = buf.get(i).toInt() and 0xff; val g = buf.get(i + 1).toInt() and 0xff; val b = buf.get(i + 2).toInt() and 0xff
                sum += r + g + b
                sgrid[base + gx] = isLeaf(r, g, b)
                dark[base + gx] = if (max(r, max(g, b)) < 45) 1 else 0
            }
        }
        black = sum < n * 6L
        // 1) tronco: columnas mayormente "hoja" en el tercio del medio
        val colCnt = IntArray(gw)
        val ya = gh / 3; val yb = 2 * gh / 3
        for (gy in ya until yb) { val base = gy * gw; for (gx in 0 until gw) if (sgrid[base + gx]) colCnt[gx]++ }
        var bestA = -1; var bestB = -1; var curA = -1; var lastX = -100
        for (gx in 0 until gw) {
            if (colCnt[gx] * 2 > (yb - ya)) {
                if (gx - lastX > 3) curA = gx
                lastX = gx
                if (lastX - curA > bestB - bestA) { bestA = curA; bestB = lastX }
            }
        }
        if (bestA < 0 || bestB - bestA < 4) { zzHud = "no encuentro el bambú"; zigInfo = zzHud; return }
        val tl = bestA; val tr = bestB
        // 2) base del tronco
        var ground = -1
        var run = 0
        for (gy in gh - 1 downTo gh / 3) {
            var k = 0; val base = gy * gw
            for (gx in tl..tr) if (sgrid[base + gx]) k++
            if (k * 10 >= (tr - tl + 1) * 6) { run++; if (run >= 6) { ground = gy + run - 1; break } } else run = 0
        }
        if (ground < 0) { zzHud = "no encuentro la base del bambú"; zigInfo = zzHud; return }
        // 3) alto de cada tramo: anillos oscuros del bambú
        val bright = FloatArray(gh)
        for (gy in gh / 4 until ground) {
            var acc = 0; var k = 0
            val row = (y0 + gy * sy) * rs
            for (gx in tl + 1 until tr) {
                val i = row + (x0 + gx * sx) * ps
                acc += max(buf.get(i).toInt() and 0xff, buf.get(i + 1).toInt() and 0xff); k++
            }
            bright[gy] = if (k > 0) acc.toFloat() / k else 0f
        }
        val sorted = bright.copyOfRange(gh / 4, ground).sorted()
        if (sorted.isNotEmpty()) {
            val med = sorted[sorted.size / 2]
            val rings = ArrayList<Float>()
            var st = -1
            for (gy in gh / 4 until ground) {
                val d = bright[gy] < med * 0.55f
                if (d && st < 0) st = gy
                if (!d && st >= 0) { rings.add((st + gy - 1) / 2f); st = -1 }
            }
            if (rings.size >= 3) {
                val diffs = (1 until rings.size).map { rings[it] - rings[it - 1] }.filter { it > 10 }.sorted()
                if (diffs.isNotEmpty()) {
                    val segNow = diffs[diffs.size / 2]
                    njSeg = if (njSeg == 0f) segNow else njSeg * 0.8f + segNow * 0.2f
                }
            }
        }
        if (njSeg <= 0f) { zzHud = "midiendo el bambú…"; zigInfo = zzHud; return }
        val seg = njSeg
        // 4) ramas a cada lado → nivel (0 = a la altura del ninja, 1 = justo encima)
        val wz = gw
        val levels = HashMap<Int, String>()
        var worst = 0f
        for (side in 0..1) {
            val a = if (side == 0) tl - (wz * 17 / 100) else tr + (wz * 3 / 100)
            val b = if (side == 0) tl - (wz * 3 / 100) else tr + (wz * 17 / 100)
            val ca = max(0, a); val cb = min(gw - 1, b)
            if (cb - ca < 3) continue
            var st = -1
            for (gy in 0..ground) {
                var k = 0; val base = gy * gw
                if (gy < ground) for (gx in ca..cb) if (sgrid[base + gx]) k++
                val on = gy < ground && k * 100 > (cb - ca + 1) * 15
                if (on && st < 0) st = gy
                if (!on && st >= 0) {
                    if (gy - st >= 2) {
                        val yc = (st + gy - 1) / 2f
                        if (yc < ground - 0.3f * seg) {
                            val f = (ground - yc) / seg - 0.5f - 0.08f
                            val lv = Math.round(f)
                            worst = max(worst, abs(f - lv))
                            if (lv <= 4) levels[lv] = (levels[lv] ?: "") + (if (side == 0) "L" else "R")
                        }
                    }
                    st = -1
                }
            }
        }
        // 5) lado del ninja: píxeles muy oscuros abajo a cada lado del tronco
        var nl = 0; var nr = 0
        val ry0 = max(0, (ground - 1.3f * seg).toInt()); val ry1 = min(gh - 1, ground + 20)
        for (gy in ry0..ry1) { val base = gy * gw; for (gx in 0 until gw) if (dark[base + gx] == 1.toByte()) { if (gx < tl) nl++ else if (gx > tr) nr++ } }
        val player = if (nl >= nr) 0 else 1
        val sig = (0..4).joinToString("") { (levels[it] ?: "-").padEnd(2, ' ') } + player
        val stable = worst < 0.2f && sig == njPrevSig
        njPrevSig = sig
        zzHud = String.format("ninja %s · encima: %s · %s", if (player == 0) "izq" else "der",
            (levels[1] ?: "nada").replace("L", "rama izq").replace("R", "rama der"), if (stable) "quieto" else "moviéndose")
        zigInfo = zzHud
        if (!active || !stable || now < njLockUntil || (sig == njLastSig && now < njLockUntil + 600)) return
        // 6) planear 2 toques: en cada paso, lado contrario a la rama de ese nivel
        val taps = ArrayList<Int>()
        var cur = player
        for (k in 1..2) {
            val b = levels[k]
            if (b != null && b.length >= 2) break   // raro: ramas de los dos lados
            cur = when (b) { "L" -> 1; "R" -> 0; else -> cur }
            taps.add(cur)
        }
        if (taps.isEmpty()) return
        njLastSig = sig
        njLockUntil = now + c.ninjaGap.toLong() * taps.size + 80
        zzLastTapInfo = "toques: " + taps.joinToString(" ") { if (it == 0) "izq" else "der" }
        for ((i, side) in taps.withIndex()) {
            val tx = (if (side == 0) w * 0.2f else w * 0.8f) * scX
            val ty = h * 0.75f * scY
            main.postDelayed({
                if (active && cfg.mode == Cfg.MODE_NINJA) { tapCount++; ClickerService.instance?.tap(tx, ty) }
            }, (i * c.ninjaGap).toLong())
        }
    }


    // =====================================================================
    // MODO ESTACIONAR: cada toque empuja el auto hacia la derecha (gira ~50°
    // y en ~300 ms vuelve a apuntar abajo). Piloto: elegir un lugar libre
    // (recuadro amarillo) más abajo, calcular el ángulo necesario y tocar
    // cada vez que el auto apunta más abajo de lo necesario.
    // =====================================================================
    private var pkPhi = -1f          // ángulo del auto: 0 = abajo, 90 = derecha (suavizado)
    private var pkTargetY = -1f
    private var pkLastTap = 0L

    private fun parkFrame(
        buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        scX: Float, scY: Float, now: Long, c: Cfg
    ) {
        val st = 3
        val gw = (x1 - x0) / st; val gh = (y1 - y0) / st
        if (gw < 20 || gh < 20) return
        val n = gw * gh
        if (sgrid.size < n) sgrid = BooleanArray(n)
        if (queue.size < n) { queue = IntArray(n); label = IntArray(n) }
        val xEntry = x0 + (x1 - x0) * 0.83f
        val carMaxGx = ((x1 - x0) * 0.81f / st).toInt()
        val carMinGx = ((x1 - x0) * 0.08f / st).toInt()
        val slA = ((x1 - x0) * 0.90f / st).toInt(); val slB = ((x1 - x0) * 0.96f / st).toInt()
        val yelRow = IntArray(gh)
        val ex = exclude
        var sum = 0L
        for (gy in 0 until gh) {
            val y = y0 + gy * st
            val row = y * rs
            val base = gy * gw
            for (gx in 0 until gw) {
                val x = x0 + gx * st
                val i = row + x * ps
                val r = buf.get(i).toInt() and 0xff; val g = buf.get(i + 1).toInt() and 0xff; val b = buf.get(i + 2).toInt() and 0xff
                sum += r + g + b
                var car = false
                if (gx in carMinGx..carMaxGx) {
                    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                    car = mx - mn > 60
                    if (car && ex.isNotEmpty()) {
                        val sx = (x * scX).toInt(); val sy = (y * scY).toInt()
                        for (rc in ex) if (rc.contains(sx, sy)) { car = false; break }
                    }
                }
                sgrid[base + gx] = car
                label[base + gx] = 0
                if (gx in slA..slB && r > 200 && g > 190 && b < 90) yelRow[gy]++
            }
        }
        black = sum < n * 6L

        // 1) lugares libres: tramos de filas con el borde amarillo
        val slots = ArrayList<Float>()
        var sA = -1
        for (gy in 0..gh) {
            val on = gy < gh && yelRow[gy] >= 1
            if (on && sA < 0) sA = gy
            if (!on && sA >= 0) { if (gy - sA >= 8) slots.add(y0 + ((sA + gy - 1) / 2f) * st); sA = -1 }
        }

        // 2) auto: mancha de color más avanzada hacia la derecha (área mínima)
        val minCells = max(20, n / 2500)
        var best: FloatArray? = null
        var lab = 0
        for (s0 in 0 until n) {
            if (!sgrid[s0] || label[s0] != 0) continue
            lab++
            var qh = 0; var qt = 0
            queue[qt++] = s0; label[s0] = lab
            var cnt = 0; var sx = 0.0; var sy = 0.0
            var bx0 = gw; var bx1 = 0; var by0 = gh; var by1 = 0
            while (qh < qt) {
                val p = queue[qh++]; val px = p % gw; val py = p / gw
                cnt++; sx += px; sy += py
                if (px < bx0) bx0 = px; if (px > bx1) bx1 = px; if (py < by0) by0 = py; if (py > by1) by1 = py
                if (px > 0) { val q = p - 1; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (px < gw - 1) { val q = p + 1; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py > 0) { val q = p - gw; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
                if (py < gh - 1) { val q = p + gw; if (sgrid[q] && label[q] == 0) { label[q] = lab; queue[qt++] = q } }
            }
            if (cnt < minCells || cnt > n / 40) continue
            val cx = x0 + (sx / cnt).toFloat() * st; val cy = y0 + (sy / cnt).toFloat() * st
            if (cx > x0 + (x1 - x0) * 0.75f) continue
            if (best == null || cx > best[0]) best = floatArrayOf(cx, cy, 0f,
                (x0 + bx0 * st).toFloat(), (x0 + bx1 * st).toFloat(), (y0 + by0 * st).toFloat(), (y0 + by1 * st).toFloat())
        }
        if (best == null) { zzHud = "no veo el auto · libres ${slots.size}"; zigInfo = zzHud; pkPhi = -1f; return }
        val cx = best[0]; val cy = best[1]
        val fx0 = max(0, best[3].toInt() - 6); val fx1 = min(w - 1, best[4].toInt() + 6)
        val fy0 = max(0, best[5].toInt() - 6); val fy1 = min(h - 1, best[6].toInt() + 6)
        val pxs = ArrayList<Float>(); val pys = ArrayList<Float>()
        for (yy in fy0..fy1) {
            val row = yy * rs
            for (xx in fx0..fx1) {
                val i = row + xx * ps
                val r = buf.get(i).toInt() and 0xff; val g = buf.get(i + 1).toInt() and 0xff; val b = buf.get(i + 2).toInt() and 0xff
                if (max(r, max(g, b)) - min(r, min(g, b)) > 60) { pxs.add(xx.toFloat()); pys.add(yy.toFloat()) }
            }
        }
        var phiNow = Float.NaN
        if (pxs.size >= 30) {
            var bestA = 0f; var bestLong = 0; var bestArea = Float.MAX_VALUE
            var a = 0
            while (a < 180) {
                val rr = Math.toRadians(a.toDouble()); val co = Math.cos(rr).toFloat(); val si = Math.sin(rr).toFloat()
                var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
                for (k in pxs.indices) {
                    val u = pxs[k] * co + pys[k] * si; val v = -pxs[k] * si + pys[k] * co
                    if (u < u0) u0 = u; if (u > u1) u1 = u; if (v < v0) v0 = v; if (v > v1) v1 = v
                }
                val area = (u1 - u0) * (v1 - v0)
                if (area < bestArea) { bestArea = area; bestA = a.toFloat(); bestLong = if (u1 - u0 >= v1 - v0) a else (a + 90) % 180 }
                a += 5
            }
            phiNow = abs(90f - bestLong)     // 0 = derecho (hacia arriba), 90 = horizontal (hacia los lugares)
        }
        if (!phiNow.isNaN()) pkPhi = if (pkPhi < 0f) phiNow else pkPhi * 0.6f + phiNow * 0.4f
        if (pkPhi < 0f) { zzHud = "midiendo el auto…"; zigInfo = zzHud; return }

        // 3) elegir lugar: más abajo que el auto y alcanzable con un ángulo razonable
        val dx = xEntry - cx
        fun need(ys: Float) = Math.toDegrees(Math.atan2(dx.toDouble(), (cy - ys).toDouble())).toFloat()
        var target = -1f
        if (pkTargetY > 0f) {
            val near = slots.minByOrNull { abs(it - pkTargetY) }
            if (near != null && abs(near - pkTargetY) < (y1 - y0) * 0.12f && near < cy && need(near) < 86f) target = near
        }
        if (target < 0f) {
            for (ys in slots.sortedDescending()) {
                if (ys >= cy) continue
                val a = need(ys)
                if (a in 15f..72f) { target = ys; break }
            }
        }
        pkTargetY = target
        if (target < 0f) {
            zzHud = String.format("auto %d° · sin lugar alcanzable (libres %d)", pkPhi.toInt(), slots.size)
            zigInfo = zzHud; return
        }
        // 4) ángulo deseado: hacia la entrada del lugar; al final, horizontal
        val phiD = if (dx < (x1 - x0) * 0.07f) 90f else need(target)
        zzHud = String.format("auto %d° · quiere %d° · lugar a %d px", pkPhi.toInt(), phiD.toInt(), (cy - target).toInt())
        zigInfo = zzHud
        if (!active) return
        if (pkPhi < phiD - 8f && now - pkLastTap >= c.parkGap) {
            pkLastTap = now
            tapCount++
            val tx = w * 0.4f * scX; val ty = h * 0.85f * scY
            fire { ClickerService.instance?.tap(tx, ty) }
        }
    }


    /** Arma una imagen chica del sector: colores reales, y en verde lo detectado. */
    private fun buildPreview(gw: Int, gh: Int, n: Int) {
        val raw = cols.copyOf(n)
        val shown = IntArray(n)
        for (i in 0 until n) {
            if (grid[i]) shown[i] = Color.rgb(0, 255, 90)
            else {
                val c = raw[i]
                // atenuado para que lo detectado resalte
                val r = (Color.red(c) * 0.55f).toInt(); val g = (Color.green(c) * 0.55f).toInt(); val b = (Color.blue(c) * 0.55f).toInt()
                shown[i] = Color.rgb(r, g, b)
            }
        }
        previewRaw = raw; previewW = gw; previewH = gh
        val bmp = try { Bitmap.createBitmap(shown, gw, gh, Bitmap.Config.ARGB_8888) } catch (_: Exception) { return }
        main.post { ClickerService.instance?.onPreview(bmp) }
    }

    /** Toma el color de la vista previa en la posición relativa (0..1). */
    fun pickFromPreview(ctx: Context, fx: Float, fy: Float): Int? {
        val raw = previewRaw ?: return null
        val w = previewW; val h = previewH
        if (w == 0 || h == 0) return null
        val px = (fx * w).toInt().coerceIn(0, w - 1)
        val py = (fy * h).toInt().coerceIn(0, h - 1)
        // promedio 3x3 para evitar bordes
        var r = 0; var g = 0; var b = 0; var k = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = px + dx; val y = py + dy
            if (x in 0 until w && y in 0 until h) {
                val c = raw[y * w + x]; r += Color.red(c); g += Color.green(c); b += Color.blue(c); k++
            }
        }
        val col = Color.rgb(r / k, g / k, b / k)
        save(ctx, cfg.copy(color = col))
        return col
    }

    private fun sampleCenter(buf: ByteBuffer, x0: Int, y0: Int, x1: Int, y1: Int, rs: Int, ps: Int): Int {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val rad = max(1, min(x1 - x0, y1 - y0) / 6)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in max(y0, cy - rad) until min(y1, cy + rad + 1)) {
            for (x in max(x0, cx - rad) until min(x1, cx + rad + 1)) {
                val i = y * rs + x * ps
                r += buf.get(i).toInt() and 0xff
                g += buf.get(i + 1).toInt() and 0xff
                b += buf.get(i + 2).toInt() and 0xff
                n++
            }
        }
        if (n == 0) return cfg.color
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}

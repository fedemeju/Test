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
    val zigLead: Int = 70        // zigzag: anticipación del giro (ms, compensa la demora)
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
            zigLead = p.getInt("zigLeadMs", d.zigLead)
        )
    }

    fun save(ctx: Context, c: Cfg) {
        cfg = c
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt("rx", c.rx).putInt("ry", c.ry).putInt("rw", c.rw).putInt("rh", c.rh)
            .putInt("tx", c.tx).putInt("ty", c.ty).putInt("mode", c.mode).putInt("color", c.color)
            .putInt("hueTol2", c.hueTol).putInt("minSize", c.minSize)
            .putInt("intervalMs", c.intervalMs).putInt("cooldownMs2", c.cooldownMs)
            .putBoolean("repeat", c.repeat).putBoolean("multi", c.multi).putBoolean("full", c.full).putInt("reactMs", c.reactMs).putInt("mathMin", c.mathMinMs).putInt("mathMax", c.mathMaxMs).putInt("zigLeadMs", c.zigLead).apply()
    }

    // ---------- Estado ----------
    fun setActive(ctx: Context, on: Boolean) {
        active = on && capturing && !editing && cfg.ready
        wasMatch = false
        recentT.fill(0)
        MathSolver.reset()
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
                    if (wd >= rad * 2.5f && tc <= wd * 0.5f * 0.35f + zzV * c.zigLead) {
                        tapNow = true
                        zzDir = 1 - zzDir
                        zzLock = now + 130
                        zzVx = 0f; zzPrevCur = 0f
                    }
                }
            }
            zigInfo = String.format(
                "Bola: sí · va hacia %s",
                when (zzDir) { 0 -> "↖"; 1 -> "↗"; else -> "? (tocá vos para empezar)" }
            ) + cInfo
        } else {
            zigInfo = "No encuentro la bola dentro del marco"
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

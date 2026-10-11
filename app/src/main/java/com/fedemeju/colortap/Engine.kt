package com.fedemeju.colortap

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Configuración guardada. Coordenadas en píxeles absolutos de pantalla. */
data class Cfg(
    val rx: Int = -1, val ry: Int = -1, val rw: Int = 0, val rh: Int = 0, // sector a vigilar
    val tx: Int = -1, val ty: Int = -1,                                    // dónde tocar
    val color: Int = Color.rgb(229, 57, 53),                               // color objetivo
    val hueTol: Int = 15,        // tolerancia de tono (grados)
    val minPct: Int = 30,        // % mínimo del sector que debe coincidir
    val intervalMs: Int = 30,    // cada cuánto se revisa la pantalla
    val cooldownMs: Int = 300,   // espera mínima entre toques
    val repeat: Boolean = false  // seguir tocando mientras el color siga ahí
) {
    val hasRegion get() = rw > 0 && rh > 0 && rx >= 0 && ry >= 0
    val hasTarget get() = tx >= 0 && ty >= 0
}

object Engine {
    @Volatile var cfg = Cfg()
    @Volatile var capturing = false   // captura de pantalla activa
    @Volatile var active = false      // detección + toques activos
    @Volatile var editing = false     // modo de ajuste de sector/punto
    @Volatile var lastPct = 0         // % de coincidencia del último frame
    @Volatile var sampleRequest = false
    @Volatile var tapCount = 0

    private var lastCheck = 0L
    private var lastTap = 0L
    private var wasMatch = false
    private val hsv = FloatArray(3)
    private val thsv = FloatArray(3)
    private val main = Handler(Looper.getMainLooper())

    // ---------- Persistencia ----------
    private const val P = "colortap"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val d = Cfg()
        cfg = Cfg(
            rx = p.getInt("rx", d.rx), ry = p.getInt("ry", d.ry),
            rw = p.getInt("rw", d.rw), rh = p.getInt("rh", d.rh),
            tx = p.getInt("tx", d.tx), ty = p.getInt("ty", d.ty),
            color = p.getInt("color", d.color),
            hueTol = p.getInt("hueTol", d.hueTol),
            minPct = p.getInt("minPct", d.minPct),
            intervalMs = p.getInt("intervalMs", d.intervalMs),
            cooldownMs = p.getInt("cooldownMs", d.cooldownMs),
            repeat = p.getBoolean("repeat", d.repeat)
        )
    }

    fun save(ctx: Context, c: Cfg) {
        cfg = c
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt("rx", c.rx).putInt("ry", c.ry).putInt("rw", c.rw).putInt("rh", c.rh)
            .putInt("tx", c.tx).putInt("ty", c.ty).putInt("color", c.color)
            .putInt("hueTol", c.hueTol).putInt("minPct", c.minPct)
            .putInt("intervalMs", c.intervalMs).putInt("cooldownMs", c.cooldownMs)
            .putBoolean("repeat", c.repeat).apply()
    }

    // ---------- Estado ----------
    fun setActive(ctx: Context, on: Boolean) {
        active = on && capturing && !editing && cfg.hasRegion && cfg.hasTarget
        wasMatch = false
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

    // ---------- Detección (se llama desde el hilo de captura) ----------
    fun onFrame(ctx: Context, buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int) {
        if (!active && !editing) return
        val now = System.currentTimeMillis()
        val c = cfg
        if (now - lastCheck < c.intervalMs) return
        lastCheck = now
        if (!c.hasRegion) return

        // En modo edición el marco se dibuja sobre el sector: lo excluimos
        val inset = if (editing) ClickerService.BORDER_PX else 0
        val x0 = max(0, c.rx + inset); val y0 = max(0, c.ry + inset)
        val x1 = min(w, c.rx + c.rw - inset); val y1 = min(h, c.ry + c.rh - inset)
        if (x1 <= x0 || y1 <= y0) return

        if (sampleRequest) {
            sampleRequest = false
            val col = sampleCenter(buf, x0, y0, x1, y1, rowStride, pixStride)
            save(ctx, c.copy(color = col))
            main.post { ClickerService.instance?.onColorSampled(col) }
            return
        }

        Color.colorToHSV(c.color, thsv)
        val sx = max(1, (x1 - x0) / 60); val sy = max(1, (y1 - y0) / 60)
        var total = 0; var hits = 0
        var y = y0
        while (y < y1) {
            var x = x0
            val row = y * rowStride
            while (x < x1) {
                val i = row + x * pixStride
                val r = buf.get(i).toInt() and 0xff
                val g = buf.get(i + 1).toInt() and 0xff
                val b = buf.get(i + 2).toInt() and 0xff
                Color.RGBToHSV(r, g, b, hsv)
                if (matches(hsv, thsv, c.hueTol)) hits++
                total++
                x += sx
            }
            y += sy
        }
        val pct = if (total == 0) 0 else hits * 100 / total
        lastPct = pct

        if (!active) return
        val match = pct >= c.minPct
        if (match && (c.repeat || !wasMatch) && now - lastTap >= c.cooldownMs) {
            lastTap = now
            tapCount++
            ClickerService.instance?.tap(c.tx.toFloat(), c.ty.toFloat())
        }
        wasMatch = match
    }

    private fun matches(p: FloatArray, t: FloatArray, hueTol: Int): Boolean {
        // Colores sin saturación (blanco, gris, negro): comparar sólo brillo
        if (t[1] < 0.2f) return p[1] < 0.25f && abs(p[2] - t[2]) < 0.2f
        var dh = abs(p[0] - t[0])
        if (dh > 180f) dh = 360f - dh
        return dh <= hueTol &&
            p[1] >= max(0.2f, t[1] - 0.35f) &&
            abs(p[2] - t[2]) <= 0.4f
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

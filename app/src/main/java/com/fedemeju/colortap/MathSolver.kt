package com.fedemeju.colortap

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random

/**
 * Modo "Math quiz": lee la cuenta dentro del marco, la resuelve,
 * busca debajo del marco el botón con el resultado y lo toca
 * (con una demora al azar para no contestar a velocidad de máquina).
 */
object MathSolver {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val main = Handler(Looper.getMainLooper())

    @Volatile var info = ""
    @Volatile private var busy = false
    private var lastRun = 0L
    private var prevQ = ""           // pregunta leída en la vuelta anterior (para confirmar que está estable)
    private var answeredQ = ""
    private var answeredAt = 0L
    @Volatile private var pending = false

    fun reset() {
        prevQ = ""; answeredQ = ""; answeredAt = 0L; pending = false
        main.removeCallbacksAndMessages(null)
    }

    /** Se llama desde el hilo de captura. Coordenadas del marco en píxeles de captura. */
    fun onFrame(
        buf: ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int,
        bx0: Int, by0: Int, bx1: Int, by1: Int, scX: Float, scY: Float,
        now: Long, c: Cfg, exclude: List<Rect>
    ) {
        if (busy || pending || now - lastRun < 120) return
        lastRun = now
        // recortar desde el marco hacia abajo (cuenta + opciones)
        val top = by0.coerceIn(0, h - 1)
        val bmp: Bitmap
        try {
            val pxW = rowStride / pixStride
            val full = Bitmap.createBitmap(pxW, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            full.copyPixelsFromBuffer(buf)
            val crop = Bitmap.createBitmap(full, 0, top, w, h - top)
            full.recycle()
            val s = if (w > 800) 0.6f else 1f
            bmp = if (s < 1f) {
                val sc = Bitmap.createScaledBitmap(crop, (crop.width * s).toInt(), (crop.height * s).toInt(), true)
                crop.recycle(); sc
            } else crop
        } catch (_: Throwable) { return }
        val s = bmp.width.toFloat() / w
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                try {
                    handle(text, s, top, bx0, by0, bx1, by1, scX, scY, c, exclude)
                } catch (_: Throwable) {}
            }
            .addOnCompleteListener {
                busy = false
                bmp.recycle()
            }
    }

    private class Opt(val value: Double, val x: Float, val y: Float)

    private fun handle(
        text: com.google.mlkit.vision.text.Text, s: Float, top: Int,
        bx0: Int, by0: Int, bx1: Int, by1: Int, scX: Float, scY: Float, c: Cfg, exclude: List<Rect>
    ) {
        val qParts = ArrayList<Pair<Float, String>>()
        val opts = ArrayList<Opt>()
        for (block in text.textBlocks) for (line in block.lines) {
            val bb = line.boundingBox ?: continue
            // a coordenadas de captura
            val cx = bb.exactCenterX() / s
            val cy = bb.exactCenterY() / s + top
            val inBox = cx >= bx0 && cx <= bx1 && cy >= by0 && cy <= by1
            if (inBox) {
                qParts.add(cy to line.text)
            } else if (cy > by1) {
                val sx = (cx * scX).toInt(); val sy = (cy * scY).toInt()
                if (exclude.any { it.contains(sx, sy) }) continue
                val num = parseNumber(line.text) ?: continue
                opts.add(Opt(num, cx * scX, cy * scY))
            }
        }
        qParts.sortBy { it.first }
        val rawQ = qParts.joinToString(" ") { it.second }
        val q = normalize(rawQ)
        if (q.isEmpty() || !q.any { it.isDigit() }) { info = "Buscando la cuenta en el marco…"; prevQ = ""; return }

        val matches = opts.filter { o -> checks(q, o.value) }
        val ansTxt = when {
            opts.isEmpty() -> "sin opciones debajo"
            matches.isEmpty() -> "ninguna opción coincide"
            else -> fmt(matches[0].value)
        }
        info = "Cuenta: $rawQ\n→ $ansTxt   (opciones: ${opts.joinToString(" ") { fmt(it.value) }})"

        val stable = q == prevQ
        prevQ = q
        if (!Engine.active || !stable || matches.isEmpty()) return
        val now = System.currentTimeMillis()
        // no contestar dos veces la misma pregunta (salvo que siga ahí mucho rato: toque perdido)
        if (q == answeredQ && now - answeredAt < 2500) return

        // si varias coinciden (raro), elegir la más grande en pantalla no tiene sentido: usar la primera
        val target = matches[0]
        answeredQ = q; answeredAt = now
        val lo = minOf(c.mathMinMs, c.mathMaxMs); val hi = maxOf(c.mathMinMs, c.mathMaxMs)
        val delay = if (hi > lo) Random.nextInt(lo, hi + 1) else lo
        pending = true
        main.postDelayed({
            pending = false
            if (Engine.active && Engine.cfg.mode == Cfg.MODE_MATH) {
                Engine.tapCount++
                ClickerService.instance?.tap(target.x, target.y)
            }
        }, delay.toLong())
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && abs(v) < 1e12) v.toLong().toString() else String.format("%.2f", v)

    private fun parseNumber(t: String): Double? {
        val cl = t.trim().replace(" ", "")
            .replace('O', '0').replace('o', '0')
            .replace('l', '1').replace('I', '1')
            .replace('−', '-').replace('–', '-')
            .replace(',', '.')
        if (!Regex("^-?\\d+(\\.\\d+)?$").matches(cl)) return null
        return cl.toDoubleOrNull()
    }

    /** Deja sólo números, operadores, ?, = y paréntesis. */
    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when (ch) {
                in '0'..'9', '+', '-', '?', '=', '(', ')', '.' -> sb.append(ch)
                '×', 'x', 'X', '*', '·', '•' -> sb.append('*')
                '÷', ':', '/' -> sb.append('/')
                '−', '–', '—' -> sb.append('-')
                ',' -> sb.append('.')
                '¿' -> sb.append('?')
                else -> {}
            }
        }
        return sb.toString()
    }

    /** ¿El valor v es la respuesta correcta para la cuenta q? */
    fun checks(q0: String, v: Double): Boolean {
        var q = q0.trimEnd('=')
        if (!q.contains('=')) {
            // "37+6" o "37+6?" → calcular y comparar
            val e = q.replace("?", "")
            val r = eval(e) ?: return false
            return abs(r - v) < 1e-6
        }
        val parts = q.split('=')
        if (parts.size != 2) return false
        var lhs = parts[0]; var rhs = parts[1]
        if (rhs.isEmpty()) rhs = "?"
        if (!lhs.contains('?') && !rhs.contains('?')) {
            // sin incógnita explícita: la respuesta es el lado izquierdo
            val r = eval(lhs) ?: return false
            return abs(r - v) < 1e-6
        }
        val vs = "(" + fmt(v).replace(",", ".") + ")"
        val l = eval(lhs.replace("?", vs)) ?: return false
        val r = eval(rhs.replace("?", vs)) ?: return false
        return abs(l - r) < 1e-6
    }

    // ---- evaluador simple con precedencia (+ - * / y paréntesis) ----
    private class P(val s: String) { var i = 0 }

    fun eval(s: String): Double? {
        if (s.isEmpty()) return null
        return try {
            val p = P(s)
            val v = expr(p)
            if (p.i != s.length) null else v
        } catch (_: Exception) { null }
    }

    private fun expr(p: P): Double {
        var v = term(p)
        while (p.i < p.s.length) {
            val c = p.s[p.i]
            if (c == '+') { p.i++; v += term(p) }
            else if (c == '-') { p.i++; v -= term(p) }
            else break
        }
        return v
    }

    private fun term(p: P): Double {
        var v = factor(p)
        while (p.i < p.s.length) {
            val c = p.s[p.i]
            if (c == '*') { p.i++; v *= factor(p) }
            else if (c == '/') { p.i++; val d = factor(p); if (d == 0.0) throw ArithmeticException(); v /= d }
            else break
        }
        return v
    }

    private fun factor(p: P): Double {
        if (p.i >= p.s.length) throw IllegalStateException()
        val c = p.s[p.i]
        if (c == '-') { p.i++; return -factor(p) }
        if (c == '(') {
            p.i++
            val v = expr(p)
            if (p.i >= p.s.length || p.s[p.i] != ')') throw IllegalStateException()
            p.i++
            return v
        }
        val st = p.i
        while (p.i < p.s.length && (p.s[p.i].isDigit() || p.s[p.i] == '.')) p.i++
        if (st == p.i) throw IllegalStateException()
        return p.s.substring(st, p.i).toDouble()
    }
}

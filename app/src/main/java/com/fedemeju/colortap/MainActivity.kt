package com.fedemeju.colortap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_CAPTURE = 10
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var a11yBtn: Button
    private lateinit var geom: TextView
    private lateinit var colorField: EditText
    private lateinit var swatch: View
    private lateinit var hueField: EditText
    private lateinit var pctField: EditText
    private lateinit var intervalField: EditText
    private lateinit var cooldownField: EditText
    private lateinit var repeatBox: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(32))
        }

        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        }
        fun note(t: String) = TextView(this).apply {
            text = t; textSize = 13.5f; setTextColor(Color.DKGRAY); setPadding(0, 0, 0, dp(8))
        }
        fun field(label: String, value: String, numeric: Boolean = true): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; textSize = 15f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val et = EditText(this).apply {
                setText(value); textSize = 15f; gravity = Gravity.END
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            }
            row.addView(et, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(row)
            return et
        }

        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        root.addView(status)

        root.addView(title("1. Permiso de accesibilidad"))
        root.addView(note("Sirve para tocar la pantalla y mostrar la burbuja. Buscá \"ColorTap\" en la lista y activalo.\n" +
            "Si aparece bloqueado (\"configuración restringida\"), tocá \"Info de la app\" abajo → menú ⋮ arriba a la derecha → \"Permitir configuración restringida\", y volvé a intentar."))
        a11yBtn = Button(this).apply {
            text = "Abrir ajustes de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(a11yBtn)
        root.addView(Button(this).apply {
            text = "Info de la app"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        root.addView(title("2. Iniciar"))
        root.addView(note("Cuando Android pregunte qué compartir, elegí \"Pantalla completa\" (no \"Una sola app\")."))
        startBtn = Button(this).apply { setOnClickListener { onStartStop() } }
        root.addView(startBtn)

        root.addView(title("3. Cómo se usa"))
        root.addView(note(
            "• Aparece una burbuja flotante encima de todo.\n" +
            "• Mantenela apretada → modo ajuste: movés el marco amarillo sobre el sector a vigilar (esquina de abajo a la derecha para cambiar el tamaño) y la mira celeste adonde querés que toque.\n" +
            "• Con lo que buscás visible dentro del marco, tocá \"Tomar color\". El panel te muestra el % de coincidencia en vivo.\n" +
            "• \"Guardar\" y después tocá la burbuja: verde = activo, gris = pausa.\n" +
            "• También podés agregar el botón \"ColorTap\" en los ajustes rápidos (menú que bajás desde arriba)."))

        geom = note("")
        root.addView(geom)

        root.addView(title("Ajustes"))
        val c = Engine.cfg
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colorRow.addView(TextView(this).apply { text = "Color (hex)"; textSize = 15f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        swatch = View(this)
        colorRow.addView(swatch, LinearLayout.LayoutParams(dp(28), dp(28)))
        colorField = EditText(this).apply {
            textSize = 15f; gravity = Gravity.END; inputType = InputType.TYPE_CLASS_TEXT
        }
        colorRow.addView(colorField, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(colorRow)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Rojo" to "#E53935", "Verde" to "#43A047", "Azul" to "#1E88E5", "Amarillo" to "#FDD835").forEach { (n, h) ->
            presets.addView(Button(this).apply {
                text = n; textSize = 12f; isAllCaps = false
                setOnClickListener { colorField.setText(h); updateSwatch() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(presets)

        hueField = field("Tolerancia de tono (°)", c.hueTol.toString())
        pctField = field("% mínimo del sector", c.minPct.toString())
        intervalField = field("Revisar cada (ms)", c.intervalMs.toString())
        cooldownField = field("Espera entre toques (ms)", c.cooldownMs.toString())
        repeatBox = CheckBox(this).apply {
            text = "Seguir tocando mientras el color siga ahí"; isChecked = c.repeat; textSize = 15f
        }
        root.addView(repeatBox)
        root.addView(note("Sin tildar: toca una sola vez cada vez que el color aparece."))
        root.addView(Button(this).apply { text = "Guardar ajustes"; setOnClickListener { saveSettings() } })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.load(this)
        colorField.setText(String.format("#%06X", Engine.cfg.color and 0xFFFFFF))
        updateSwatch()
        refresh()
    }

    private fun updateSwatch() {
        val col = parseColor(colorField.text.toString()) ?: Engine.cfg.color
        swatch.background = GradientDrawable().apply { setColor(col); cornerRadius = dp(4).toFloat(); setStroke(dp(1), Color.GRAY) }
    }

    private fun parseColor(s: String): Int? = try {
        Color.parseColor(if (s.trim().startsWith("#")) s.trim() else "#" + s.trim())
    } catch (_: Exception) { null }

    private fun refresh() {
        val a11y = ClickerService.instance != null
        status.text = buildString {
            append(if (a11y) "✅ Accesibilidad activada" else "❌ Accesibilidad desactivada")
            append("\n")
            append(if (Engine.capturing) "✅ Captura activa" else "⏸ Captura detenida")
        }
        a11yBtn.text = if (a11y) "Accesibilidad: activada ✔" else "Abrir ajustes de accesibilidad"
        startBtn.text = if (Engine.capturing) "Detener" else "Iniciar captura"
        val c = Engine.cfg
        geom.text = if (c.hasRegion && c.hasTarget)
            "Sector: ${c.rw}×${c.rh} px en (${c.rx}, ${c.ry})  ·  Toque en (${c.tx}, ${c.ty})"
        else "Sector y punto de toque: todavía sin configurar (se hace desde la burbuja)."
    }

    private fun saveSettings() {
        val col = parseColor(colorField.text.toString())
        if (col == null) { Toast.makeText(this, "Color inválido. Ej: #E53935", Toast.LENGTH_SHORT).show(); return }
        fun num(e: EditText, def: Int, lo: Int, hi: Int) = (e.text.toString().toIntOrNull() ?: def).coerceIn(lo, hi)
        val c = Engine.cfg
        Engine.save(this, c.copy(
            color = col or (0xFF shl 24),
            hueTol = num(hueField, c.hueTol, 1, 90),
            minPct = num(pctField, c.minPct, 1, 100),
            intervalMs = num(intervalField, c.intervalMs, 0, 5000),
            cooldownMs = num(cooldownField, c.cooldownMs, 0, 60000),
            repeat = repeatBox.isChecked
        ))
        updateSwatch()
        Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
    }

    private fun onStartStop() {
        if (Engine.capturing) {
            CaptureService.stop(this)
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        if (ClickerService.instance == null) {
            Toast.makeText(this, "Primero activá el permiso de accesibilidad (paso 1)", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "No se dio permiso de captura", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "¡Listo! Abrí el juego y usá la burbuja.", Toast.LENGTH_LONG).show()
        startBtn.postDelayed({ refresh() }, 500)
    }
}

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
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var sizeField: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var reactField: EditText
    private lateinit var mathMinField: EditText
    private lateinit var mathMaxField: EditText
    private lateinit var zigField: EditText
    private lateinit var stackField: EditText
    private lateinit var ninjaField: EditText
    private lateinit var multiBox: CheckBox
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
            "• En el juego, tocá la burbuja y elegí: Empezar, Editar zona o Toda la pantalla.\n" +
            "• En Editar zona, el marco amarillo es la zona que se vigila: arrastralo para moverlo, el triángulo de la esquina cambia el tamaño, o tocá \"Pantalla entera\". Después \"▶ Empezar\".\n" +
            "• El panel muestra una vista previa de lo que ve la app: lo pintado en VERDE es lo que detecta. Si los círculos no se pintan de verde, tocá uno en la vista previa para tomar su color exacto.\n" +
            "• Mientras está activo (burbuja azul), tocala para pausar. Cada toque hace parpadear la burbuja.\n" +
            "• Modo \"tocar el color\": toca encima de cada círculo que aparece. Modo \"punto fijo\": cuando aparece el color, toca siempre en la mira celeste.\n" +
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
        sizeField = field("Tamaño mínimo del círculo (px)", c.minSize.toString())
        intervalField = field("Revisar cada (ms)", c.intervalMs.toString())
        cooldownField = field("Espera entre toques (ms)", c.cooldownMs.toString())
        reactField = field("Carriles: anticipación (ms)", c.reactMs.toString())
        mathMinField = field("Math quiz: demora mínima (ms)", c.mathMinMs.toString())
        mathMaxField = field("Math quiz: demora máxima (ms)", c.mathMaxMs.toString())
        zigField = field("Zigzag: anticipación (ms)", c.zigLead.toString())
        stackField = field("Stack: anticipación (ms)", c.stackLead.toString())
        ninjaField = field("Ninja: ms entre toques", c.ninjaGap.toString())
        root.addView(title("Modo"))
        modeGroup = RadioGroup(this)
        listOf(
            Cfg.MODE_ON_COLOR to "Tocar sobre el color (blancos)",
            Cfg.MODE_FIXED to "Punto fijo (toca la mira cuando aparece el color)",
            Cfg.MODE_LANES to "Carriles (2 naves que esquivan bloques)",
            Cfg.MODE_MATH to "Math quiz (lee la cuenta y toca la respuesta)",
            Cfg.MODE_ZIGZAG to "Zigzag (gira la bola en cada curva)",
            Cfg.MODE_STACK to "Stack (apila los bloques alineados)",
            Cfg.MODE_NINJA to "Ninja (corta el bambú esquivando ramas)"
        ).forEach { (m, t) ->
            modeGroup.addView(RadioButton(this).apply { id = 100 + m; text = t; textSize = 15f })
        }
        modeGroup.check(100 + c.mode)
        root.addView(modeGroup)
        root.addView(note("Carriles: el marco tiene que cubrir el área de juego de lado a lado (sin el puntaje de arriba). Si esquiva tarde, subí la anticipación; si esquiva demasiado temprano, bajala."))
        multiBox = CheckBox(this).apply {
            text = "Multitoque: tocar todos los círculos a la vez"; isChecked = c.multi; textSize = 15f
        }
        root.addView(multiBox)
        repeatBox = CheckBox(this).apply {
            text = "Punto fijo: seguir tocando mientras el color siga ahí"; isChecked = c.repeat; textSize = 15f
        }
        root.addView(repeatBox)
        root.addView(note("Si toca cosas que no son círculos, subí el tamaño mínimo o bajá la tolerancia. Si no detecta, hacé lo contrario."))
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
        geom.text = if (c.hasRegion)
            "Zona: ${c.rw}×${c.rh} px en (${c.rx}, ${c.ry})" +
                (when (c.mode) { Cfg.MODE_FIXED -> "  ·  Toque fijo en (${c.tx}, ${c.ty})"; Cfg.MODE_LANES -> "  ·  Modo carriles"; Cfg.MODE_MATH -> "  ·  Math quiz"; Cfg.MODE_ZIGZAG -> "  ·  Zigzag"; Cfg.MODE_STACK -> "  ·  Stack"; Cfg.MODE_NINJA -> "  ·  Ninja"; else -> "  ·  Toca sobre el color" }) +
                "\nToques hechos: ${Engine.tapCount}"
        else "Zona a vigilar: todavía sin configurar (se hace desde la burbuja)."
        sizeField.setText(c.minSize.toString()); hueField.setText(c.hueTol.toString())
        cooldownField.setText(c.cooldownMs.toString()); modeGroup.check(100 + c.mode); reactField.setText(c.reactMs.toString()); mathMinField.setText(c.mathMinMs.toString()); mathMaxField.setText(c.mathMaxMs.toString()); zigField.setText(c.zigLead.toString()); stackField.setText(c.stackLead.toString()); ninjaField.setText(c.ninjaGap.toString())
    }

    private fun saveSettings() {
        val col = parseColor(colorField.text.toString())
        if (col == null) { Toast.makeText(this, "Color inválido. Ej: #E53935", Toast.LENGTH_SHORT).show(); return }
        fun num(e: EditText, def: Int, lo: Int, hi: Int) = (e.text.toString().toIntOrNull() ?: def).coerceIn(lo, hi)
        val c = Engine.cfg
        Engine.save(this, c.copy(
            color = col or (0xFF shl 24),
            hueTol = num(hueField, c.hueTol, 1, 90),
            minSize = num(sizeField, c.minSize, 2, 500),
            mode = (modeGroup.checkedRadioButtonId - 100).coerceIn(0, 6),
            ninjaGap = num(ninjaField, c.ninjaGap, 30, 1000),
            stackLead = num(stackField, c.stackLead, 0, 400),
            zigLead = num(zigField, c.zigLead, 0, 400),
            mathMinMs = num(mathMinField, c.mathMinMs, 0, 10000),
            mathMaxMs = num(mathMaxField, c.mathMaxMs, 0, 10000),
            reactMs = num(reactField, c.reactMs, 50, 2000),
            intervalMs = num(intervalField, c.intervalMs, 0, 5000),
            cooldownMs = num(cooldownField, c.cooldownMs, 0, 60000),
            repeat = repeatBox.isChecked,
            multi = multiBox.isChecked
        ))
        // Al pasar a modo carriles con el rojo por defecto, usar el rosado de los bloques
        val nc = Engine.cfg
        if (nc.mode == Cfg.MODE_LANES && c.mode != Cfg.MODE_LANES && (nc.color and 0xFFFFFF) == 0xE53935) {
            Engine.save(this, nc.copy(color = Cfg.PINK, hueTol = 20))
            colorField.setText(String.format("#%06X", Cfg.PINK and 0xFFFFFF)); hueField.setText("20")
        }
        if (Engine.cfg.mode == Cfg.MODE_ZIGZAG && c.mode != Cfg.MODE_ZIGZAG) {
            Engine.save(this, Engine.cfg.copy(color = Cfg.ZIG_YELLOW, hueTol = 15))
            colorField.setText(String.format("#%06X", Cfg.ZIG_YELLOW and 0xFFFFFF)); hueField.setText("15")
        }
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

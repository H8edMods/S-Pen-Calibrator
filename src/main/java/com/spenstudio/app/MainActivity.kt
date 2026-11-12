package com.spenstudio.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** x' = a*x + b*y + c ; y' = d*x + e*y + f  (shift, stretch and skew correction) */
data class Affine(
    val a: Float = 1f, val b: Float = 0f, val c: Float = 0f,
    val d: Float = 0f, val e: Float = 1f, val f: Float = 0f
) {
    fun x(px: Float, py: Float): Float = a * px + b * py + c
    fun y(px: Float, py: Float): Float = d * px + e * py + f

    fun isSane(): Boolean =
        a in 0.8f..1.2f && e in 0.8f..1.2f &&
            abs(b) < 0.2f && abs(d) < 0.2f &&
            abs(c) < 300f && abs(f) < 300f

    companion object {
        val IDENTITY = Affine()

        fun fit(raw: List<FloatArray>, truth: List<FloatArray>): Affine? {
            val n = raw.size
            if (n < 3 || truth.size != n) return null
            var sxx = 0.0; var sxy = 0.0; var sx = 0.0; var syy = 0.0; var sy = 0.0
            var sxtx = 0.0; var sytx = 0.0; var stx = 0.0
            var sxty = 0.0; var syty = 0.0; var sty = 0.0
            for (i in 0 until n) {
                val x = raw[i][0].toDouble(); val y = raw[i][1].toDouble()
                val tx = truth[i][0].toDouble(); val ty = truth[i][1].toDouble()
                sxx += x * x; sxy += x * y; sx += x; syy += y * y; sy += y
                sxtx += x * tx; sytx += y * tx; stx += tx
                sxty += x * ty; syty += y * ty; sty += ty
            }
            val m = arrayOf(
                doubleArrayOf(sxx, sxy, sx),
                doubleArrayOf(sxy, syy, sy),
                doubleArrayOf(sx, sy, n.toDouble())
            )
            val px = solve(m, doubleArrayOf(sxtx, sytx, stx)) ?: return null
            val py = solve(m, doubleArrayOf(sxty, syty, sty)) ?: return null
            return Affine(
                px[0].toFloat(), px[1].toFloat(), px[2].toFloat(),
                py[0].toFloat(), py[1].toFloat(), py[2].toFloat()
            )
        }

        private fun det3(m: Array<DoubleArray>): Double =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        private fun solve(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
            val d = det3(m)
            if (abs(d) < 1e-9) return null
            val out = DoubleArray(3)
            for (k in 0..2) {
                val mk = Array(3) { r -> DoubleArray(3) { c -> if (c == k) rhs[r] else m[r][c] } }
                out[k] = det3(mk) / d
            }
            return out
        }
    }
}

class CalibrationView(context: Context) : View(context) {

    private val sp = context.getSharedPreferences("spen_cal", Context.MODE_PRIVATE)

    // 5 columns wide x 8 rows tall = 40 targets, left-to-right, top-to-bottom
    private val cols = 5
    private val rows = 8
    private val targets: List<Pair<Float, Float>> = (0 until rows).flatMap { r ->
        (0 until cols).map { c ->
            (0.1f + 0.2f * c) to (0.2f + 0.74f * r / (rows - 1))
        }
    }

    private var index = 0
    private val raws = ArrayList<FloatArray>()
    private var calibrating = true
    private var affine = loadAffine()
    private var status = "Target 1 of 40: tap the highlighted crosshair with your S Pen tip"

    private val strokes = ArrayList<FloatArray>()
    private var lastX = 0f
    private var lastY = 0f
    private var drawing = false

    private var hx = -1f
    private var hy = -1f
    private var pressure = 0f
    private var tiltDeg = 0f
    private var distance = 0f
    private var button = false

    private fun spx(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = spx(13f) }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A"); style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5560"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7"); strokeCap = Paint.Cap.ROUND
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A"); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    private fun loadAffine(): Affine {
        val s = sp.getString("affine", null) ?: return Affine.IDENTITY
        val p = s.split(",")
        if (p.size != 6) return Affine.IDENTITY
        return try {
            Affine(
                p[0].toFloat(), p[1].toFloat(), p[2].toFloat(),
                p[3].toFloat(), p[4].toFloat(), p[5].toFloat()
            )
        } catch (e: NumberFormatException) {
            Affine.IDENTITY
        }
    }

    private fun saveAffine(v: Affine) {
        sp.edit().putString("affine", "${v.a},${v.b},${v.c},${v.d},${v.e},${v.f}").apply()
    }

    fun restart() {
        index = 0
        raws.clear()
        calibrating = true
        status = "Target 1 of 40: tap the highlighted crosshair with your S Pen tip"
        strokes.clear()
        invalidate()
    }

    fun resetCorrection() {
        affine = Affine.IDENTITY
        saveAffine(Affine.IDENTITY)
        status = "Correction reset to none"
        invalidate()
    }

    fun clearCanvas() {
        strokes.clear()
        invalidate()
    }

    private fun readAxes(e: MotionEvent) {
        pressure = e.pressure
        tiltDeg = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT).toDouble()).toFloat()
        distance = e.getAxisValue(MotionEvent.AXIS_DISTANCE)
        button = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                hx = -1f; hy = -1f
            } else {
                hx = e.x; hy = e.y
            }
            readAxes(e)
            invalidate()
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            status = "Finger input ignored. Use the S Pen."
            invalidate()
            return true
        }
        readAxes(e)
        hx = e.x; hy = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (calibrating) {
                    recordTarget(e.x, e.y)
                } else {
                    drawing = true
                    lastX = affine.x(e.x, e.y)
                    lastY = affine.y(e.x, e.y)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!calibrating && drawing) {
                val nx = affine.x(e.x, e.y)
                val ny = affine.y(e.x, e.y)
                strokes.add(floatArrayOf(lastX, lastY, nx, ny, 2f + e.pressure * 12f))
                if (strokes.size > 6000) strokes.removeAt(0)
                lastX = nx; lastY = ny
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawing = false
        }
        invalidate()
        return true
    }

    private fun recordTarget(x: Float, y: Float) {
        raws.add(floatArrayOf(x, y))
        index++
        if (index >= targets.size) {
            finish()
        } else {
            status = "Target ${index + 1} of ${targets.size}: tap the highlighted crosshair"
        }
    }

    private fun rms(raw: List<FloatArray>, truth: List<FloatArray>, a: Affine): Float {
        var sum = 0.0
        for (i in raw.indices) {
            val dx = a.x(raw[i][0], raw[i][1]) - truth[i][0]
            val dy = a.y(raw[i][0], raw[i][1]) - truth[i][1]
            sum += (dx * dx + dy * dy).toDouble()
        }
        return sqrt(sum / raw.size).toFloat()
    }

    private fun finish() {
        val truth = targets.map { floatArrayOf(it.first * width, it.second * height) }
        var fit = Affine.fit(raws, truth)
        var note = ""
        if (fit == null || !fit.isSane()) {
            var sdx = 0f
            var sdy = 0f
            for (i in raws.indices) {
                sdx += truth[i][0] - raws[i][0]
                sdy += truth[i][1] - raws[i][1]
            }
            fit = Affine(c = sdx / raws.size, f = sdy / raws.size)
            note = " Taps were inconsistent, so a simple shift was used."
        }
        val before = rms(raws, truth, Affine.IDENTITY)
        val after = rms(raws, truth, fit)
        val mmPerPx = 25.4f / resources.displayMetrics.xdpi

        affine = fit
        saveAffine(fit)
        calibrating = false
        status = "Error before %.1f px (%.2f mm), after %.1f px (%.2f mm).%s Draw to test."
            .format(before, before * mmPerPx, after, after * mmPerPx, note)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#101418"))

        if (calibrating) {
            for (i in targets.indices) {
                val (fx, fy) = targets[i]
                val cx = fx * width
                val cy = fy * height
                when {
                    i < index -> canvas.drawCircle(cx, cy, 12f, donePaint)
                    i == index -> {
                        canvas.drawCircle(cx, cy, 30f, targetPaint)
                        canvas.drawLine(cx - 44f, cy, cx + 44f, cy, targetPaint)
                        canvas.drawLine(cx, cy - 44f, cx, cy + 44f, targetPaint)
                    }
                    else -> {
                        canvas.drawCircle(cx, cy, 18f, dimPaint)
                        canvas.drawLine(cx - 10f, cy, cx + 10f, cy, dimPaint)
                        canvas.drawLine(cx, cy - 10f, cx, cy + 10f, dimPaint)
                    }
                }
            }
        } else {
            for (s in strokes) {
                strokePaint.strokeWidth = max(2f, s[4])
                canvas.drawLine(s[0], s[1], s[2], s[3], strokePaint)
            }
        }

        if (hx >= 0f) {
            val cx = if (calibrating) hx else affine.x(hx, hy)
            val cy = if (calibrating) hy else affine.y(hx, hy)
            canvas.drawCircle(cx, cy, 20f, hoverPaint)
        }

        val line = spx(13f) * 1.4f
        var y = line + 8f
        canvas.drawText(status, 16f, y, textPaint); y += line
        canvas.drawText(
            "Correction: shift %.1f, %.1f px  scale %.3f, %.3f".format(affine.c, affine.f, affine.a, affine.e),
            16f, y, textPaint
        ); y += line
        canvas.drawText(
            "Pressure: %.2f   Tilt: %.0f deg   Hover: %.2f".format(pressure, tiltDeg, distance),
            16f, y, textPaint
        ); y += line
        canvas.drawText("S Pen button: ${if (button) "PRESSED" else "up"}", 16f, y, textPaint)
    }
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = CalibrationView(this)

        fun btn(label: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { action() }
            return b
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(btn("Recalibrate") { view.restart() }, lp())
        row.addView(btn("Reset") { view.resetCorrection() }, lp())
        row.addView(btn("Clear") { view.clearCanvas() }, lp())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)
    }
}

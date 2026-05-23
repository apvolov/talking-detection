package ru.apvolov.talkingdetection.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ru.apvolov.talkingdetection.analysis.LipMotionAnalyzer
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Прозрачный overlay поверх превью камеры.
 * Отрисовывает:
 *   1. Контур губ (зелёный = говорит, красный = молчит)
 *   2. Точки ключевых лицевых маркеров на губах
 *   3. Статус и числовые метрики
 *   4. График MAR(t) в нижней части экрана
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Данные для отрисовки
    private var landmarks: List<NormalizedLandmark>? = null
    private var analysisResult: LipMotionAnalyzer.AnalysisResult? = null
    private var marHistory: List<Float> = emptyList()

    // --- Кисти ---

    private val talkingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")  // ярко-зелёный
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val silentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")  // ярко-красный
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val metricTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val chartLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private val chartBgPaint = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val thresholdPaint = Paint().apply {
        color = Color.argb(200, 255, 200, 0)  // жёлтая пунктирная линия
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val chartLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        textSize = 28f
    }

    // ---

    /**
     * Обновить данные для отрисовки и перерисовать
     */
    fun update(
        landmarks: List<NormalizedLandmark>,
        result: LipMotionAnalyzer.AnalysisResult,
        marHistory: List<Float>
    ) {
        this.landmarks = landmarks
        this.analysisResult = result
        this.marHistory = marHistory
        invalidate()  // запрос перерисовки
    }

    /**
     * Сбросить overlay (лицо не найдено)
     */
    fun clear() {
        this.landmarks = null
        this.analysisResult = null
        this.marHistory = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val lm = landmarks
        val res = analysisResult

        if (lm == null || res == null) {
            // Нет лица — показываем подсказку
            drawNoFaceHint(canvas)
            return
        }

        val linePaint = if (res.isTalking) talkingLinePaint else silentLinePaint

        drawLipOuterContour(canvas, lm, linePaint)
        drawLipInnerContour(canvas, lm, linePaint)
        drawKeyDots(canvas, lm)
        drawStatusPanel(canvas, res)
        drawMarChart(canvas, marHistory, res)
    }

    // ──────────────────────────────────────────────
    // Отрисовка контуров губ
    // ──────────────────────────────────────────────

    private fun drawLipOuterContour(
        canvas: Canvas,
        lm: List<NormalizedLandmark>,
        paint: Paint
    ) {
        val path = buildPath(lm, LipMotionAnalyzer.LIP_OUTER_CONTOUR)
        canvas.drawPath(path, paint)
    }

    private fun drawLipInnerContour(
        canvas: Canvas,
        lm: List<NormalizedLandmark>,
        paint: Paint
    ) {
        val innerPaint = Paint(paint).apply { alpha = 160 }
        val path = buildPath(lm, LipMotionAnalyzer.LIP_INNER_CONTOUR)
        canvas.drawPath(path, innerPaint)
    }

    private fun buildPath(lm: List<NormalizedLandmark>, indices: List<Int>): Path {
        val path = Path()
        indices.forEachIndexed { i, idx ->
            if (idx >= lm.size) return@forEachIndexed
            val x = lm[idx].x() * width   // x → ширина
            val y = lm[idx].y() * height  // y → высота
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    // ──────────────────────────────────────────────
    // Ключевые точки (4 опорные)
    // ──────────────────────────────────────────────

    private fun drawKeyDots(canvas: Canvas, lm: List<NormalizedLandmark>) {
        val keyIndices = listOf(
            LipMotionAnalyzer.LIP_TOP,
            LipMotionAnalyzer.LIP_BOTTOM,
            LipMotionAnalyzer.LIP_LEFT,
            LipMotionAnalyzer.LIP_RIGHT
        )
        for (idx in keyIndices) {
            if (idx >= lm.size) continue
            val x = lm[idx].x() * width
            val y = lm[idx].y() * height
            canvas.drawCircle(x, y, 6f, dotPaint)
        }
    }

    // ──────────────────────────────────────────────
    // Панель статуса и метрик (верхний левый угол)
    // ──────────────────────────────────────────────

    private fun drawStatusPanel(canvas: Canvas, res: LipMotionAnalyzer.AnalysisResult) {
        // Фон панели
        val panelRect = RectF(20f, 20f, 420f, 290f)
        canvas.drawRoundRect(panelRect, 16f, 16f, chartBgPaint)

        // Статус
        val statusText = if (res.isTalking) "🗣  ГОВОРИТ" else "🤫  МОЛЧИТ"
        statusTextPaint.color = if (res.isTalking)
            Color.parseColor("#00E676") else Color.parseColor("#FF5252")
        canvas.drawText(statusText, 36f, 76f, statusTextPaint)

        // Метрики
        metricTextPaint.color = Color.WHITE
        canvas.drawText("MAR:        %.3f".format(res.mar),       36f, 128f, metricTextPaint)
        canvas.drawText("Частота:  %.1f Гц".format(res.frequency), 36f, 172f, metricTextPaint)
        canvas.drawText("Амплит.:  %.3f".format(res.amplitude),   36f, 216f, metricTextPaint)

        // Шкала уверенности
        val barLeft = 36f; val barRight = 390f; val barY = 258f; val barH = 16f
        canvas.drawRoundRect(RectF(barLeft, barY, barRight, barY + barH), 8f, 8f,
            Paint().apply { color = Color.DKGRAY })
        val filled = barLeft + (barRight - barLeft) * res.confidence
        canvas.drawRoundRect(RectF(barLeft, barY, filled, barY + barH), 8f, 8f,
            Paint().apply {
                color = if (res.isTalking) Color.parseColor("#00E676")
                        else Color.parseColor("#FF5252")
            })
        metricTextPaint.textSize = 28f
        canvas.drawText("Уверенность: %.0f%%".format(res.confidence * 100),
            barLeft, barY - 4f, metricTextPaint)
        metricTextPaint.textSize = 36f
    }

    // ──────────────────────────────────────────────
    // График MAR(t) — нижняя часть экрана
    // ──────────────────────────────────────────────

    private fun drawMarChart(
        canvas: Canvas,
        history: List<Float>,
        res: LipMotionAnalyzer.AnalysisResult
    ) {
        if (history.size < 2) return

        val chartTop    = height * 0.76f
        val chartBottom = height.toFloat() - 16f
        val chartLeft   = 20f
        val chartRight  = width.toFloat() - 20f
        val chartH      = chartBottom - chartTop

        // Фон
        canvas.drawRoundRect(
            RectF(chartLeft, chartTop - 32f, chartRight, chartBottom),
            12f, 12f, chartBgPaint
        )

        // Подпись
        canvas.drawText("MAR / время (${history.size} кадров)",
            chartLeft + 8f, chartTop - 8f, chartLabelPaint)

        val maxMar = history.max().coerceAtLeast(0.08f)

        // Линия порога MAR_THRESHOLD
        val thresholdY = chartBottom - (LipMotionAnalyzer.MAR_THRESHOLD / maxMar) * chartH
        canvas.drawLine(chartLeft, thresholdY, chartRight, thresholdY, thresholdPaint)

        // Сигнал MAR
        val path = Path()
        history.forEachIndexed { i, mar ->
            val x = chartLeft + (i.toFloat() / (history.size - 1)) * (chartRight - chartLeft)
            val y = chartBottom - (mar / maxMar) * chartH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Цвет кривой зависит от статуса
        chartLinePaint.color = if (res.isTalking)
            Color.parseColor("#00E676") else Color.CYAN
        canvas.drawPath(path, chartLinePaint)

        // Текущее значение MAR в конце кривой
        val lastX = chartRight - 4f
        val lastMar = history.last()
        val lastY = chartBottom - (lastMar / maxMar) * chartH
        canvas.drawCircle(lastX, lastY, 6f, Paint().apply {
            color = chartLinePaint.color; style = Paint.Style.FILL
        })
    }

    // ──────────────────────────────────────────────

    private fun drawNoFaceHint(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = 44f
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        canvas.drawText("Направьте камеру на лицо",
            width / 2f, height / 2f, paint)
    }
}

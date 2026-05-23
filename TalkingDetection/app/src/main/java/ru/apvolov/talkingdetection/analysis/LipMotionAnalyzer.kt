package ru.apvolov.talkingdetection.analysis

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Анализатор движения губ на основе метода Zero-Crossing.
 *
 * Алгоритм:
 * 1. Вычисляем MAR (Mouth Aspect Ratio) для каждого кадра
 * 2. Накапливаем историю MAR в скользящем окне
 * 3. Считаем количество пересечений среднего значения (zero-crossing)
 * 4. Частота движений = (кол-во пересечений / 2) / время_окна
 * 5. Если частота в диапазоне речи (1.5–7 Гц) и амплитуда достаточна → говорит
 */
class LipMotionAnalyzer {

    companion object {
        // Скользящее окно — 2 секунды при 30 fps
        const val WINDOW_SIZE = 60

        // Порог раскрытия рта (MAR)
        const val MAR_THRESHOLD = 0.015f

        // Диапазон частот речи в Гц
        const val MIN_SPEECH_FREQ = 1.5f
        const val MAX_SPEECH_FREQ = 7.0f

        // Индексы точек губ в MediaPipe Face Mesh (478 точек)
        // Верхняя губа (центр)
        const val LIP_TOP = 13
        // Нижняя губа (центр)
        const val LIP_BOTTOM = 14
        // Левый угол рта
        const val LIP_LEFT = 61
        // Правый угол рта
        const val LIP_RIGHT = 291

        const val VOTE_WINDOW = 15  // голосуем по 15 кадрам
        private val talkingHistory = ArrayDeque<Boolean>(VOTE_WINDOW)

        // Контур губ для отрисовки (внешний + внутренний)
        val LIP_OUTER_CONTOUR = listOf(
            61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291,
            308, 324, 318, 402, 317, 14, 87, 178, 88, 95, 78, 61
        )
        val LIP_INNER_CONTOUR = listOf(
            78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308,
            415, 310, 311, 312, 13, 82, 81, 80, 191, 78
        )
    }

    // Хранилище MAR-значений (кольцевой буфер через ArrayDeque)
    private val marHistory = ArrayDeque<Float>(WINDOW_SIZE)
    // Временные метки кадров (мс)
    private val timestampHistory = ArrayDeque<Long>(WINDOW_SIZE)

    /**
     * Результат анализа одного кадра
     */
    data class AnalysisResult(
        val mar: Float,            // текущий MAR
        val isTalking: Boolean,    // говорит?
        val frequency: Float,      // частота движений губ, Гц
        val amplitude: Float,      // амплитуда MAR в окне
        val confidence: Float      // уверенность 0..1
    )

    /**
     * Обработать один кадр — список лицевых точек от MediaPipe
     */
    fun analyze(landmarks: List<NormalizedLandmark>): AnalysisResult {
        val mar = calculateMAR(landmarks)
        val now = System.currentTimeMillis()

        // Добавляем в историю
        marHistory.addLast(mar)
        timestampHistory.addLast(now)

        // Ограничиваем размер окна
        while (marHistory.size > WINDOW_SIZE) {
            marHistory.removeFirst()
            timestampHistory.removeFirst()
        }

        val amplitude = calculateAmplitude()
        val frequency = estimateFrequencyByZeroCrossing()
        // val isTalking = smoothedTalking(detectTalking(frequency, amplitude))
        val isTalking = detectTalking(frequency, amplitude)
        val confidence = calculateConfidence(frequency, amplitude)

        return AnalysisResult(
            mar = mar,
            isTalking = isTalking,
            frequency = frequency,
            amplitude = amplitude,
            confidence = confidence
        )
    }

    /**
     * MAR — Mouth Aspect Ratio
     * Отношение вертикального раскрытия рта к его ширине.
     * MAR = |верх - низ| / |лево - право|
     */
    private fun calculateMAR(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.size < 300) return 0f

        val top = landmarks[LIP_TOP]
        val bottom = landmarks[LIP_BOTTOM]
        val left = landmarks[LIP_LEFT]
        val right = landmarks[LIP_RIGHT]

        val vertical = Math.abs(top.y() - bottom.y())
        val horizontal = Math.abs(right.x() - left.x())

        return if (horizontal > 0.001f) vertical / horizontal else 0f
    }

    /**
     * Амплитуда = размах MAR в скользящем окне (max - min)
     */
    private fun calculateAmplitude(): Float {
        if (marHistory.size < 2) return 0f
        return marHistory.max() - marHistory.min()
    }

    /**
     * Zero-Crossing: оцениваем частоту движений губ.
     *
     * Считаем, сколько раз MAR пересекает среднее значение.
     * Частота = (кол-во пересечений / 2) / длительность_окна_в_секундах
     */
    private fun estimateFrequencyByZeroCrossing(): Float {
        if (marHistory.size < 10) return 0f

        val mean = marHistory.average().toFloat()
        var crossings = 0
        var prevAboveMean = marHistory.first() > mean

        for (mar in marHistory) {
            val aboveMean = mar > mean
            if (aboveMean != prevAboveMean) {
                crossings++
                prevAboveMean = aboveMean
            }
        }

        // Длительность окна в секундах
        val windowSec = if (timestampHistory.size >= 2) {
            (timestampHistory.last() - timestampHistory.first()) / 1000f
        } else {
            marHistory.size / 30f  // fallback: считаем 30 fps
        }

        return if (windowSec > 0f) (crossings / 2f) / windowSec else 0f
    }

    /**
     * Человек говорит если:
     * - амплитуда движений губ выше порога (рот реально открывается)
     * - частота пересечений в диапазоне речи (1.5–7 Гц)
     */
    private fun detectTalking(frequency: Float, amplitude: Float): Boolean {
        return amplitude > MAR_THRESHOLD && frequency in MIN_SPEECH_FREQ..MAX_SPEECH_FREQ
    }

    /**
     * Уверенность: комбинируем оценку частоты и амплитуды
     */
    private fun calculateConfidence(frequency: Float, amplitude: Float): Float {
        if (amplitude < MAR_THRESHOLD) return 0f

        val freqScore = when {
            frequency < MIN_SPEECH_FREQ -> (frequency / MIN_SPEECH_FREQ).coerceIn(0f, 1f)
            frequency > MAX_SPEECH_FREQ -> (MAX_SPEECH_FREQ / frequency).coerceIn(0f, 1f)
            else -> 1f
        }

        val ampScore = (amplitude / 0.08f).coerceIn(0f, 1f)
        return freqScore * 0.6f + ampScore * 0.4f
    }

    private fun smoothedTalking(current: Boolean): Boolean {
        talkingHistory.addLast(current)
        if (talkingHistory.size > VOTE_WINDOW) talkingHistory.removeFirst()
        // говорит если больше половины кадров — говорит
        return talkingHistory.count { it } > VOTE_WINDOW / 2
    }

    /**
     * Получить историю MAR для отрисовки графика
     */
    fun getMarHistory(): List<Float> = marHistory.toList()

    /**
     * Сбросить историю (например, при потере лица)
     */
    fun reset() {
        marHistory.clear()
        timestampHistory.clear()
    }
}

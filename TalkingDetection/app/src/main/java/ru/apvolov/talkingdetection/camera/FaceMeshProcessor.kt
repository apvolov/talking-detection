package ru.apvolov.talkingdetection.camera

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Обёртка над MediaPipe FaceLandmarker.
 * Принимает кадры с камеры и асинхронно возвращает 478 лицевых точек.
 *
 * Модель face_landmarker.task должна лежать в assets/.
 * Скачать: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
 */
class FaceMeshProcessor(
    context: Context,
    private val onResult: (FaceLandmarkerResult) -> Unit,
    private val onError: (String) -> Unit
) {

    private var faceLandmarker: FaceLandmarker? = null

    init {
        setupFaceLandmarker(context)
    }

    private fun setupFaceLandmarker(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
                .setResultListener { result, _ ->
                    onResult(result)
                }
                .setErrorListener { error ->
                    onError(error.message ?: "Неизвестная ошибка MediaPipe")
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)

        } catch (e: Exception) {
            onError("Не удалось инициализировать MediaPipe: ${e.message}")
        }
    }

    /**
     * Отправить кадр на обработку.
     * @param bitmap — кадр с камеры
     * @param timestampMs — временная метка кадра (мс)
     */
    fun processFrame(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, timestampMs)
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}

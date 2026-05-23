package ru.apvolov.talkingdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ru.apvolov.talkingdetection.analysis.LipMotionAnalyzer
import ru.apvolov.talkingdetection.camera.FaceMeshProcessor
import ru.apvolov.talkingdetection.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var faceMeshProcessor: FaceMeshProcessor? = null
    private val lipAnalyzer = LipMotionAnalyzer()
    private lateinit var cameraExecutor: ExecutorService
    private val TAG = "TalkingDetection"

    // Текущая камера (по умолчанию фронтальная)
    private var isFrontCamera = true

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showCameraDialog()
            else {
                Toast.makeText(this, "Разрешение на камеру необходимо", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        faceMeshProcessor = FaceMeshProcessor(
            context = this,
            onResult = { result ->
                val faceList = result.faceLandmarks()
                if (faceList.isNotEmpty()) {
                    val landmarks = faceList[0]
                    val analysisResult = lipAnalyzer.analyze(landmarks)
                    val history = lipAnalyzer.getMarHistory()
                    runOnUiThread {
                        binding.overlayView.update(landmarks, analysisResult, history)
                    }
                } else {
                    lipAnalyzer.reset()
                    runOnUiThread { binding.overlayView.clear() }
                }
            },
            onError = { errorMsg ->
                Log.e(TAG, "MediaPipe error: $errorMsg")
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        )

        // Кнопка переключения камеры
        binding.btnSwitchCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            startCamera()
        }

        if (hasCameraPermission()) showCameraDialog()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Диалог выбора камеры при запуске
     */
    private fun showCameraDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Выберите камеру")
            .setItems(arrayOf("Фронтальная камера", "Задняя камера")) { _, which ->
                isFrontCamera = which == 0
                startCamera()
            }
            .setCancelable(false)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            // Выбираем камеру в зависимости от флага
            val cameraSelector = if (isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка привязки камеры: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()

        val rotation = imageProxy.imageInfo.rotationDegrees
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (isFrontCamera) preScale(-1f, 1f)
        }

        val processedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false
        )

        faceMeshProcessor?.processFrame(
            processedBitmap,
            imageProxy.imageInfo.timestamp / 1_000_000L
        )
        imageProxy.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceMeshProcessor?.close()
        cameraExecutor.shutdown()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}
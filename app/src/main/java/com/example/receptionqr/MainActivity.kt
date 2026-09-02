package com.example.receptionqr

import android.Manifest
import android.app.AlertDialog
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.receptionqr.BuildConfig
import com.example.receptionqr.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SUCCESS_GREEN = "#1B5E20"
private const val ERROR_RED = "#B71C1C"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private var isScanningLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        warmUpBackend()
        binding.statsButton.setOnClickListener {
            loadStats()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    startCamera()
                } else {
                    binding.statusText.text = "Brak uprawnień do kamery"
                    Toast.makeText(this, "Nadaj uprawnienie do kamery", Toast.LENGTH_LONG).show()
                }
            }.launch(Manifest.permission.CAMERA)
        }
    }

    private fun warmUpBackend() {
        val redeemUrl = BuildConfig.API_URL.toHttpUrlOrNull() ?: return
        val healthUrl = redeemUrl.newBuilder()
            .encodedPath("/health")
            .query(null)
            .build()

        val request = Request.Builder()
            .url(healthUrl)
            .get()
            .build()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().close()
            } catch (_: Exception) {
                // Warmup is best-effort only.
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            val scanner = BarcodeScanning.getClient(options)

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isScanningLocked) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val code = barcodes.firstOrNull()?.rawValue
                        if (!code.isNullOrBlank()) {
                            isScanningLocked = true
                            validateCode(code)
                        }
                    }
                    .addOnFailureListener {
                        Log.e("QR", "Błąd skanowania", it)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("QR", "Błąd inicjalizacji kamery", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun validateCode(code: String) {
        showResult("Łączenie z serwerem...", "#333333")

        val payload = JSONObject().apply {
            put("code", code)
            put("staff_id", "recepcja_1")
        }.toString()

        val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(BuildConfig.API_URL)
            .post(requestBody)
            .build()

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }

                val responseBody = response.body?.string().orEmpty()
                val json = if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
                val status = json.optString("status", "invalid")
                val message = json.optString("message", "Brak informacji")
                val points = json.optInt("points", -1)
                val rewardName = json.optString("reward_name", "")

                when (status) {
                    "valid" -> {
                        val successText = buildString {
                            append(message.ifBlank { "MOŻNA WYDAĆ NAGRODĘ" })
                            if (points >= 0) {
                                append("\n")
                                append(points)
                                append(" pkt")
                            }
                            if (rewardName.isNotBlank()) {
                                append("\n")
                                append(rewardName)
                            }
                        }
                        showResult(successText, SUCCESS_GREEN)
                        playSuccessEffect()
                    }
                    "already_used" -> {
                        val duplicateText = buildString {
                            append("Kod był już skanowany")
                            if (points >= 0) {
                                append("\n")
                                append(points)
                                append(" pkt")
                            }
                            if (rewardName.isNotBlank()) {
                                append("\n")
                                append(rewardName)
                            }
                        }
                        showResult(duplicateText, ERROR_RED)
                        playDuplicateEffect()
                    }
                    else -> {
                        showResult(message.ifBlank { "NIEPRAWIDŁOWY KOD" }, ERROR_RED)
                    }
                }
            } catch (exception: Exception) {
                Log.e("QR", "Błąd walidacji kodu", exception)
                showResult("Brak połączenia z serwerem", ERROR_RED)
            } finally {
                unlockScanningLater()
            }
        }
    }

    private fun loadStats() {
        showResult("Pobieranie statystyk...", "#333333")

        val redeemUrl = BuildConfig.API_URL.toHttpUrlOrNull()
        if (redeemUrl == null) {
            showResult("Błąd konfiguracji statystyk", ERROR_RED)
            return
        }

        val statsUrl = redeemUrl.newBuilder()
            .encodedPath("/api/stats")
            .query(null)
            .build()

        val request = Request.Builder()
            .url(statsUrl)
            .get()
            .build()

        lifecycleScope.launch {
            try {
                val responseBody = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}")
                        }
                        response.body?.string().orEmpty()
                    }
                }

                val json = JSONObject(responseBody)
                val scannedPlayers = json.optJSONArray("scanned_players")

                val statsMessage = if (scannedPlayers == null || scannedPlayers.length() == 0) {
                    "Brak poprawnie zeskanowanych graczy."
                } else {
                    buildString {
                        for (index in 0 until scannedPlayers.length()) {
                            val item = scannedPlayers.getJSONObject(index)
                            val rank = item.optInt("rank", index + 1)
                            val playerId = item.optString("player_id", "?").trim()
                            val points = item.optInt("points", 0)

                            append(rank)
                            append(". ")
                            append(playerId)
                            append(" — ")
                            append(points)
                            append(" pkt")

                            if (index < scannedPlayers.length() - 1) {
                                append("\n")
                            }
                        }
                    }
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Lista zeskanowanych graczy")
                    .setMessage(statsMessage)
                    .setPositiveButton("OK", null)
                    .show()

                showResult("Skanuj QR kamerą", "#CC000000")
            } catch (exception: Exception) {
                Log.e("QR", "Błąd pobierania statystyk", exception)
                showResult("Nie udało się pobrać statystyk", ERROR_RED)
            }
        }
    }

    private fun showResult(message: String, backgroundColor: String) {
        binding.statusText.text = message
        binding.statusText.setBackgroundColor(Color.parseColor(backgroundColor))
    }

    private fun playSuccessEffect() {
        binding.statusText.animate().cancel()
        binding.statusText.scaleX = 0.96f
        binding.statusText.scaleY = 0.96f
        binding.statusText.alpha = 0.88f
        binding.statusText.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        val pulse = ValueAnimator.ofFloat(1f, 1.08f, 1f)
        pulse.duration = 320
        pulse.addUpdateListener { animator ->
            val value = animator.animatedValue as Float
            binding.previewView.scaleX = value
            binding.previewView.scaleY = value
        }
        pulse.start()
    }

    private fun playDuplicateEffect() {
        binding.statusText.animate().cancel()
        val shake = ObjectAnimator.ofFloat(binding.statusText, "translationX", 0f, -18f, 18f, -12f, 12f, 0f)
        shake.duration = 320
        shake.start()
    }

    private fun unlockScanningLater() {
        lifecycleScope.launch {
            delay(3000)
            isScanningLocked = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

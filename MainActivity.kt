package com.example.ailivetransform

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var resultView: ImageView
    private lateinit var capture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private val client = OkHttpClient.Builder().callTimeout(java.time.Duration.ofMinutes(3)).build()
    private var referenceFile: File? = null
    private var latestCapture: File? = null
    private lateinit var serverField: EditText
    private lateinit var promptField: EditText

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status.text = "Camera permission is required."
    }
    private val pickReference = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val file = File(cacheDir, "reference-image")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
            referenceFile = file
            status.text = "Reference image loaded."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFF101018.toInt())
        }
        fun label(text: String, size: Float = 14f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(0xFFF4F1FF.toInt())
        }
        root.addView(label("AI LIVE TRANSFORM STUDIO", 20f))
        root.addView(label("Camera • prompt restyling • reference-guided edits"))
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        resultView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        root.addView(resultView, LinearLayout.LayoutParams(-1, 180))
        serverField = EditText(this).apply {
            setSingleLine(true); setText("https://YOUR-GPU-SERVER")
            hint = "GPU server URL"; setTextColor(-1); setHintTextColor(0xFFAAA5B8.toInt())
        }
        root.addView(serverField)
        promptField = EditText(this).apply {
            setText("Restyle this video frame as a cinematic sci-fi character, preserve pose and scene composition.")
            setTextColor(-1); setHintTextColor(0xFFAAA5B8.toInt())
        }
        root.addView(promptField, LinearLayout.LayoutParams(-1, 90))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pick = Button(this).apply { text = "Upload character"; setOnClickListener { pickReference.launch("image/*") } }
        val go = Button(this).apply { text = "Transform frame"; setOnClickListener { takeAndTransform() } }
        buttons.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(go, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        val liveNote = label("Prototype: transforms captured frames on a cloud GPU; continuous live video and RTMP output are not enabled yet.")
        root.addView(liveNote)
        status = label("Ready. Connect a compatible GPU backend.")
        root.addView(status)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
                status.text = "Camera ready. Upload a reference image and enter a prompt."
            } catch (e: Exception) { status.text = "Camera error: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeAndTransform() {
        if (!::capture.isInitialized) { status.text = "Camera is not ready."; return }
        val frame = File(cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(frame).build()
        status.text = "Capturing frame…"
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                runOnUiThread { sendFrame(frame) }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { status.text = "Capture failed: ${exception.message}" }
            }
        })
    }

    private fun sendFrame(frame: File) {
        val base = serverField.text.toString().trim().trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            status.text = "Enter your GPU backend URL, including https://"; return
        }
        val prompt = promptField.text.toString().trim()
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("image", frame.name, frame.asRequestBody("image/jpeg".toMediaType()))
        referenceFile?.let {
            bodyBuilder.addFormDataPart("reference", it.name, it.asRequestBody("image/*".toMediaType()))
        }
        status.text = "Sending frame to GPU…"
        Thread {
            try {
                val request = Request.Builder().url("$base/transform").post(bodyBuilder.build()).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("GPU server returned HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                    val bytes = response.body?.bytes() ?: throw Exception("Empty response")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("Invalid image returned")
                    runOnUiThread {
                        resultView.setImageBitmap(bitmap)
                        resultView.visibility = View.VISIBLE
                        status.text = "Transformation complete. This is a processed still frame."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Transform failed: ${e.localizedMessage}" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}

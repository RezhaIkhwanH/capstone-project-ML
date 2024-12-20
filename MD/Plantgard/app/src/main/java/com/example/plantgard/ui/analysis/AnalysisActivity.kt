package com.example.plantgard.ui.analysis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.animation.AlphaAnimation
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.plantgard.R
import com.example.plantgard.api.ApiConfig
import com.example.plantgard.databinding.ActivityAnalysisBinding
import com.example.plantgard.network.PredictionResponse
import com.example.plantgard.getImageUri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AnalysisActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAnalysisBinding
    private var currentImageUri: Uri? = null
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        progressBar = binding.progressBar // Pastikan Anda punya progress bar di layout

        // Setup window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Ambil data dari Intent
        val plantType = intent.getStringExtra("PLANT_TYPE") ?: "Tanaman tidak diketahui"
        val plantTextView: TextView = findViewById(R.id.TitleTextView)
        plantTextView.text = "Analisis $plantType"

        setupListener()
        binding.upload.setOnClickListener {
            if (currentImageUri != null) {
                // Ambil token dari SharedPreferences
                val sharedPref = getSharedPreferences("user_data", MODE_PRIVATE)
                val token = sharedPref.getString("auth_token", null)

                if (token != null) {
                    uploadImageToAPI(plantType, token)  // Mengirim data ke API
                } else {
                    showToast("Token tidak ditemukan. Silakan login terlebih dahulu.")
                }
            } else {
                showToast("Harap pilih atau ambil gambar terlebih dahulu")
            }
        }
    }

    private fun setupListener() {
        // Tombol untuk membuka kamera
        binding.cameraButton.setOnClickListener {
            if (checkAndRequestPermissions()) {
                currentImageUri = getImageUri(this) // Mendapatkan URI untuk menyimpan gambar
                cameraLaunch.launch(currentImageUri!!) // Meluncurkan intent kamera
            }
        }

        // Tombol untuk membuka galeri
        binding.galleryButton.setOnClickListener {
            launcherGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private val cameraLaunch =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                showImage()
            } else {
                currentImageUri = null
                showToast("Gagal mengambil gambar")
            }
        }

    private val launcherGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            currentImageUri = uri
            showImage()
        } else {
            showToast("Tidak ada gambar yang dipilih")
        }
    }

    private fun showImage() {
        val uri = currentImageUri
        if (uri != null) {
            Log.d("Image URI", "showImage: $uri")
            binding.previewImageView.setImageURI(uri) // Menampilkan gambar
        } else {
            Log.d("Image URI", "Tidak ditemukan URI gambar")
        }
    }

    // Fungsi untuk mengirimkan data ke API
    private fun uploadImageToAPI(plantType: String, token: String) {
        val filePath = getFilePathFromUri(currentImageUri!!)
        val file = java.io.File(filePath)
        val requestBody = RequestBody.create("image/*".toMediaTypeOrNull(), file)
        val body = MultipartBody.Part.createFormData("file", file.name, requestBody)

        val headers = mapOf("Authorization" to "Bearer $token")

        progressBar.visibility = ProgressBar.VISIBLE

        val call = ApiConfig.apiService.uploadImage(plantType, headers, body)
        call.enqueue(object : Callback<PredictionResponse> {
            override fun onResponse(call: Call<PredictionResponse>, response: Response<PredictionResponse>) {
                progressBar.visibility = ProgressBar.GONE
                if (response.isSuccessful) {
                    val predictionResponse = response.body()
                    if (predictionResponse != null) {
                        // Kirim data ke ResultActivity
                        val intent = Intent(this@AnalysisActivity, ResultActivity::class.java)
                        intent.putExtra("PLANT_TYPE", plantType)
                        intent.putExtra("imageUri", currentImageUri.toString())  // Kirim URI gambar

                        // Kirim data penyakit dari API
                        val disease = predictionResponse.data.disease
                        intent.putExtra("DISEASE_TYPE", disease.type)
                        intent.putExtra("DISEASE_DESCRIPTION", disease.description)
                        intent.putExtra("DISEASE_TREATMENT", disease.treatment)
                        intent.putExtra("DISEASE_PREVENTION", disease.prevention)

                        startActivity(intent)
                    } else {
                        showToast("Data tidak valid dari API")
                    }
                } else {
                    showToast("Gagal mengambil data:  ${response.message()}")
                }
            }

            override fun onFailure(call: Call<PredictionResponse>, t: Throwable) {
                progressBar.visibility = ProgressBar.GONE
                showToast("Kesalahan jaringan: ${t.message}")
            }
        })
    }

    // Mendapatkan file path dari URI
    private fun getFilePathFromUri(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val columnIndex = it.getColumnIndex("_data")
            it.moveToFirst()
            return it.getString(columnIndex)
        }
        return null
    }

    // Animasi loading
    private fun showLoading(isLoading: Boolean) {
        val animation = if (isLoading) {
            AlphaAnimation(0f, 1f).apply { duration = 300 }
        } else {
            AlphaAnimation(1f, 0f).apply { duration = 300 }
        }
        binding.progressBar.startAnimation(animation)
        binding.progressBar.visibility = if (isLoading) ProgressBar.VISIBLE else ProgressBar.GONE
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Mengecek dan meminta izin kamera
    private fun checkAndRequestPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        return if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            false
        } else {
            true
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }
}

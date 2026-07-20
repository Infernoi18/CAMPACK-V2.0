package com.example.campackv20

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.campackv20.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.yalantis.ucrop.UCrop
import com.signzy.imageanalysis.SignzyImageAnalysis
import com.signzy.imagequality.model.SignzyAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var capturedImageUri: Uri? = null
    private var currentPhotoFile: File? = null
    private var currentBitmap: Bitmap? = null

    // ─── Permission Launchers ────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            fetchLocation()
        } else {
            binding.statusText.text = "Location: Permission Denied"
            binding.coordsText.text = "Lat/Lng not available"
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.camStatusText.text = "Permission: Granted ✓"
            Toast.makeText(this, "Camera permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            binding.camStatusText.text = "Permission: Denied ✗"
            Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            capturedImageUri?.let { uri ->
                if (binding.cropCheckbox.isChecked) {
                    launchCrop(uri)
                } else {
                    displayAndProcess(uri)
                }
            }
        }
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val croppedUri = UCrop.getOutput(result.data!!)
            croppedUri?.let { displayAndProcess(it) }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            Toast.makeText(this, "Crop failed: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        requestLocationPermissionAndFetch()
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.requestBtn.setOnClickListener { requestCameraPermission() }
        binding.captureBtn.setOnClickListener { openCamera() }
        binding.retakeBtn.setOnClickListener { openCamera() }
        binding.showBase64Btn.setOnClickListener { copyBase64ToClipboard() }
        binding.saveGalleryBtn.setOnClickListener { saveToGallery() }
    }

    // ─── Location ────────────────────────────────────────────────────────────

    private fun requestLocationPermissionAndFetch() {
        when {
            hasLocationPermission() -> fetchLocation()
            else -> locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun fetchLocation() {
        binding.statusText.text = "Fetching location…"
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val lat = location.latitude
                        val lng = location.longitude
                        binding.statusText.text = "Location: Found ✓"
                        binding.coordsText.text = "Lat: ${"%.6f".format(lat)}   Lng: ${"%.6f".format(lng)}"
                        reverseGeocode(lat, lng)
                    } else {
                        binding.statusText.text = "Location: Unavailable"
                        binding.coordsText.text = "Could not determine position"
                    }
                }
                .addOnFailureListener {
                    binding.statusText.text = "Location: Error"
                    binding.coordsText.text = it.localizedMessage ?: "Unknown error"
                }
        } catch (e: SecurityException) {
            binding.statusText.text = "Location: Security Error"
        }
    }

    private fun reverseGeocode(lat: Double, lng: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                val address = addresses?.firstOrNull()
                val readable = when {
                    address == null -> "Address not found"
                    address.maxAddressLineIndex >= 0 -> address.getAddressLine(0)
                    else -> "${address.locality}, ${address.countryName}"
                }
                withContext(Dispatchers.Main) {
                    binding.addressText.text = readable
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.addressText.text = "Geocoder unavailable"
                }
            }
        }
    }

    // ─── Camera ──────────────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> {
                binding.camStatusText.text = "Permission: Already Granted ✓"
                Toast.makeText(this, "Camera already permitted!", Toast.LENGTH_SHORT).show()
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Please grant camera permission first.", Toast.LENGTH_SHORT).show()
            return
        }

        val photoFile = createImageFile()
        currentPhotoFile = photoFile
        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        capturedImageUri = photoUri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        cameraLauncher.launch(intent)
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("CAMPACK_${timeStamp}_", ".jpg", storageDir)
    }

    // ─── Crop ────────────────────────────────────────────────────────────────

    private fun launchCrop(sourceUri: Uri) {
        val destFile = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        val destUri = Uri.fromFile(destFile)

        val options = UCrop.Options().apply {
            setCompressionQuality(getSelectedQuality())
            // Set toolbar colors
            val black = ContextCompat.getColor(this@MainActivity, android.R.color.black)
            val neonGreen = 0xFFE8FF47.toInt()
            
            setToolbarColor(black)
            setStatusBarColor(black)
            setToolbarWidgetColor(ContextCompat.getColor(this@MainActivity, android.R.color.white)) // White icons on black bar
            setActiveControlsWidgetColor(neonGreen)

            // Enable manual/freeform cropping
            setFreeStyleCropEnabled(true)
            
            // UI Tweaks for better experience
            setToolbarTitle("Crop & Edit")
        }

        val uCropIntent = UCrop.of(sourceUri, destUri)
            .withOptions(options)
            .getIntent(this)

        cropLauncher.launch(uCropIntent)
    }

    // ─── Display & Process ───────────────────────────────────────────────────

    private fun displayAndProcess(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val quality = getSelectedQuality()
                val inputStream = contentResolver.openInputStream(uri)
                val original = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (original == null) throw Exception("Could not decode image")

                // Re-compress to selected quality
                val baos = ByteArrayOutputStream()
                original.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val compressedBytes = baos.toByteArray()
                val finalBitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                withContext(Dispatchers.Main) {
                    currentBitmap = finalBitmap
                    capturedImageUri = uri
                    binding.imageView.setImageBitmap(finalBitmap)
                    binding.imageCard.visibility = View.VISIBLE
                    
                    // Clear previous analysis results UI
                    binding.blurScoreText.visibility = View.GONE
                    binding.retakeBtn.visibility = View.GONE
                    
                    val sizeKb = compressedBytes.size / 1024
                    binding.imageInfoText.text = "Size: ${sizeKb}KB | Quality: $quality%"
                    
                    // Run Signzy Image Analysis
                    analyzeImageQuality(finalBitmap)

                    Toast.makeText(this@MainActivity, "Image ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToGallery() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "CAMPACK_$timeStamp.jpg"

                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CamPack")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(it, contentValues, null, null)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Saved to Gallery: Pictures/CamPack", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getSelectedQuality(): Int = when (binding.qualityGroup.checkedRadioButtonId) {
        R.id.q90 -> 90
        R.id.q80 -> 80
        else -> 100
    }

    // ─── Signzy Image Analysis ──────────────────────────────────────────────

    private fun analyzeImageQuality(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val signzyImageAnalysis = SignzyImageAnalysis(applicationContext)
                // Using "Document" as default docType, can be changed to "Face" if needed
                val result: SignzyAnalysisResult = signzyImageAnalysis.runImageQualityAnalysis(bitmap, "Document")

                withContext(Dispatchers.Main) {
                    displayAnalysisResult(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.blurScoreText.visibility = View.VISIBLE
                    binding.blurScoreText.text = "Analysis Error: ${e.message}"
                    binding.blurScoreText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                    binding.retakeBtn.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun displayAnalysisResult(result: SignzyAnalysisResult) {
        binding.blurScoreText.visibility = View.VISIBLE
        
        if (result.error != null) {
            binding.blurScoreText.text = "Error ${result.error}: ${result.errorMessage}"
            binding.blurScoreText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.retakeBtn.visibility = View.VISIBLE
            return
        }

        val clearScore = result.clearScore ?: 0f
        val blurScore = result.blurScore ?: 0f
        
        val displayText = "Clarity: ${"%.2f".format(clearScore)} | Blur: ${"%.2f".format(blurScore)}"
        binding.blurScoreText.text = displayText

        // Simple logic: If clarity is low, show in red and suggest retake
        if (clearScore < 0.5f) {
            binding.blurScoreText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.retakeBtn.visibility = View.VISIBLE
            Toast.makeText(this, "Image is blurry. Please retake.", Toast.LENGTH_LONG).show()
        } else {
            binding.blurScoreText.setTextColor(0xFFE8FF47.toInt()) // Neon Green
            binding.retakeBtn.visibility = View.GONE
        }
    }

    // ─── Base64 ──────────────────────────────────────────────────────────────

    private fun copyBase64ToClipboard() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No image to encode", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Save current bitmap to a temp file to pass to Base64Activity
                val tempFile = File(cacheDir, "temp_base64_image.jpg")
                tempFile.outputStream().use { 
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }

                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, Base64Activity::class.java)
                    intent.putExtra("IMAGE_PATH", tempFile.absolutePath)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to prepare image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

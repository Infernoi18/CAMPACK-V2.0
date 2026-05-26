package com.example.campack

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.campack.R
import com.example.campack.databinding.ActivityBase64Binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Base64Activity : AppCompatActivity() {

    private lateinit var binding: ActivityBase64Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBase64Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val imagePath = intent.getStringExtra("IMAGE_PATH")

        if (imagePath == null) {
            binding.base64TextView.text = getString(R.string.error_path_not_found)
            return
        }

        // Set up toolbar back button
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        // Handle Toolbar navigation click
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // "Back" button at bottom
        binding.backBtn.setOnClickListener {
            finish()
        }

        loadBase64Preview(imagePath)
    }

    private fun loadBase64Preview(imagePath: String) {
        binding.loadingProgress.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(imagePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        binding.loadingProgress.visibility = View.GONE
                        binding.base64TextView.text = getString(R.string.error_file_not_exist)
                    }
                    return@launch
                }

                val fileSize = file.length()
                val displayLimit = 10000 // Show only 10k characters
                val byteLimitForPreview = 7500 // ~10k base64 chars
                
                val fullLengthEstimate = (fileSize * 4 / 3)
                val isTruncated = fullLengthEstimate > displayLimit
                
                val previewBase64 = if (isTruncated) {
                    val previewBytes = file.inputStream().use { input ->
                        val buffer = ByteArray(byteLimitForPreview)
                        val bytesRead = input.read(buffer)
                        if (bytesRead <= 0) ByteArray(0)
                        else if (bytesRead < byteLimitForPreview) buffer.copyOf(bytesRead)
                        else buffer
                    }
                    Base64.encodeToString(previewBytes, Base64.NO_WRAP) + getString(R.string.truncated_info)
                } else {
                    val fullBytes = file.readBytes()
                    Base64.encodeToString(fullBytes, Base64.NO_WRAP)
                }

                withContext(Dispatchers.Main) {
                    binding.loadingProgress.visibility = View.GONE
                    binding.base64TextView.text = previewBase64
                    
                    if (isTruncated) {
                        Toast.makeText(this@Base64Activity, getString(R.string.displaying_preview), Toast.LENGTH_SHORT).show()
                    }

                    binding.copyBtn.setOnClickListener {
                        copyFullBase64(imagePath)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingProgress.visibility = View.GONE
                    binding.base64TextView.text = getString(R.string.error_loading_base64, e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun copyFullBase64(imagePath: String) {
        binding.copyBtn.isEnabled = false
        Toast.makeText(this, getString(R.string.preparing_base64), Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fullBytes = File(imagePath).readBytes()
                val fullBase64 = Base64.encodeToString(fullBytes, Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Base64 Photo", fullBase64)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@Base64Activity, getString(R.string.full_base64_copied), Toast.LENGTH_SHORT).show()
                    binding.copyBtn.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@Base64Activity, getString(R.string.copy_failed, e.message ?: "Unknown error"), Toast.LENGTH_SHORT).show()
                    binding.copyBtn.isEnabled = true
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

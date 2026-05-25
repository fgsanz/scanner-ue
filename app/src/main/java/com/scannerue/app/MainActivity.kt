package com.scannerue.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scannerue.app.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val httpClient = OkHttpClient()

    private val productCatalog = mapOf(
        "PROD-BAN-0912" to "Organic Cavendish Bananas (Bunch)",
        "PROD-MLK-4421" to "Clover Farms 100% Organic Whole Milk (1 Gal)",
        "PROD-SRD-3381" to "Daily Baker's Artisanal Sourdough Boule (16oz)",
        "PROD-EVO-7711" to "Bella Terra Cold-Pressed Extra Virgin Olive Oil (500ml)",
        "PROD-CHP-5529" to "Fiesta Crisp Sea Salt Tortilla Chips (13oz Bag)"
    )

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = extractBarcodeFromIntent(intent)
            if (!payload.isNullOrBlank()) {
                handleScan(payload)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scannerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleScan(binding.scannerInput.text.toString())
                true
            } else {
                false
            }
        }

        binding.scannerInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB)
            ) {
                handleScan(binding.scannerInput.text.toString())
                true
            } else {
                false
            }
        }

        binding.clearButton.setOnClickListener {
            binding.barcodeValueText.text = "-"
            binding.decodedInfoText.text = "-"
            binding.responseText.text = "-"
            binding.statusText.text = "Waiting for scan..."
            binding.scannerInput.text?.clear()
            binding.scannerInput.requestFocus()
        }

        binding.scannerInput.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        registerScannerReceiver()
    }

    override fun onStop() {
        unregisterReceiver(scannerReceiver)
        super.onStop()
    }

    private fun registerScannerReceiver() {
        val filter = IntentFilter().apply {
            // Common scanner broadcast actions across rugged Android devices.
            addAction("com.symbol.datawedge.api.RESULT_ACTION")
            addAction("com.symbol.datawedge.data_string")
            addAction("com.askey.ezwedge.scanner.ACTION")
            addAction("com.askey.scanner.ACTION")
            addAction("scanner.result")
        }
        ContextCompat.registerReceiver(
            this,
            scannerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun extractBarcodeFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        val possibleKeys = listOf(
            "com.symbol.datawedge.data_string",
            "data",
            "barcode",
            "barcode_string",
            "scanData",
            "SCAN_BARCODE1"
        )

        for (key in possibleKeys) {
            val value = intent.getStringExtra(key)
            if (!value.isNullOrBlank()) return value
        }

        return null
    }

    private fun handleScan(rawValue: String) {
        val barcode = rawValue.trim()
        if (barcode.isEmpty()) {
            return
        }

        binding.barcodeValueText.text = barcode

        val decodedText = productCatalog[barcode]?.let { name ->
            "Product ID: $barcode\nName: $name"
        } ?: "Unknown Product ID. The API may return 404 for this barcode."

        binding.decodedInfoText.text = decodedText
        binding.scannerInput.text?.clear()
        binding.scannerInput.requestFocus()

        postScanToApi(barcode)
    }

    private fun postScanToApi(productId: String) {
        binding.statusText.text = "Sending to API..."

        val encodedProductId = URLEncoder.encode(productId, StandardCharsets.UTF_8.toString())
        val request = Request.Builder()
            .url("$BASE_URL/scan/$encodedProductId")
            .post(ByteArray(0).toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.statusText.text = "Network error: ${e.message}"
                    binding.responseText.text = "No response body"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyText = response.body?.string().orEmpty()
                runOnUiThread {
                    binding.responseText.text = if (bodyText.isBlank()) "(empty body)" else bodyText

                    if (response.isSuccessful) {
                        val parsedSummary = parseSuccessBody(bodyText)
                        if (parsedSummary != null) {
                            binding.decodedInfoText.text = parsedSummary
                        }
                        binding.statusText.text = "Sent successfully (${response.code})"
                    } else {
                        val apiError = parseErrorBody(bodyText)
                        binding.statusText.text = "API error ${response.code}: $apiError"
                    }
                }
            }
        })
    }

    private fun parseSuccessBody(body: String): String? {
        return try {
            val json = JSONObject(body)
            val productId = json.optString("productId")
            val name = json.optString("name")
            val sku = json.optString("sku")
            val category = json.optString("category")
            val quantity = json.optInt("quantity", -1)

            "Product ID: $productId\nName: $name\nSKU: $sku\nCategory: $category\nQuantity: $quantity"
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorBody(body: String): String {
        return try {
            JSONObject(body).optString("error", "Unknown error")
        } catch (_: Exception) {
            body.ifBlank { "Unknown error" }
        }
    }

    companion object {
        private const val BASE_URL = "https://eod-demo-f7drswf6vq-ma.a.run.app"
    }
}

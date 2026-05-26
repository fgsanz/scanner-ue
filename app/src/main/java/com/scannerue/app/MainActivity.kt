package com.scannerue.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
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
    private val scanFinalizeHandler = Handler(Looper.getMainLooper())
    private val finalizeScanRunnable = Runnable { consumeSinkTextAndScan() }
    private val splashHandler = Handler(Looper.getMainLooper())
    private val hideSplashRunnable = Runnable {
        binding.splashOverlay.visibility = View.GONE
        showWaitingState()
        focusScannerSink()
    }
    private val pendingScans = ArrayDeque<String>()
    private var isSending = false
    private var lastQueuedBarcode = ""
    private var lastQueuedAtMs = 0L
    private var hasScannedAtLeastOnce = false

    private val productCatalog = mapOf(
        "PROD-BAN-0912" to ProductInfo("Organic Cavendish Bananas (Bunch)", "Fresh Produce / Fruits"),
        "PROD-MLK-4421" to ProductInfo("Clover Farms 100% Organic Whole Milk (1 Gal)", "Dairy & Eggs"),
        "PROD-SRD-3381" to ProductInfo("Daily Baker's Artisanal Sourdough Boule (16oz)", "Bakery / Bread"),
        "PROD-EVO-7711" to ProductInfo("Bella Terra Cold-Pressed Extra Virgin Olive Oil (500ml)", "Pantry / Oils & Vinegars"),
        "PROD-CHP-5529" to ProductInfo("Fiesta Crisp Sea Salt Tortilla Chips (13oz Bag)", "Snacks / Chips & Crackers")
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
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScannerSink()
        setupButtons()
        showSplashState()
    }

    private fun setupButtons() {
        binding.clearButton.setOnClickListener {
            clearPendingScanUi()
            showWaitingState()
        }

        binding.developerButton.setOnClickListener {
            val shouldShow = binding.developerSection.visibility != View.VISIBLE
            binding.developerSection.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (shouldShow) {
                binding.scrollContainer.post {
                    binding.scrollContainer.fullScroll(View.FOCUS_DOWN)
                }
            }
            focusScannerSink()
        }
    }

    override fun onStart() {
        super.onStart()
        registerScannerReceiver()
        focusScannerSink()
    }

    override fun onResume() {
        super.onResume()
        focusScannerSink()
        forceHideKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            forceHideKeyboard()
        }
    }

    override fun onStop() {
        scanFinalizeHandler.removeCallbacks(finalizeScanRunnable)
        splashHandler.removeCallbacks(hideSplashRunnable)
        unregisterReceiver(scannerReceiver)
        super.onStop()
    }

    private fun showSplashState() {
        binding.splashOverlay.visibility = View.VISIBLE
        updateBottomActionsVisibility(false)
        binding.waitingSection.visibility = View.VISIBLE
        binding.scannedSection.visibility = View.GONE
        splashHandler.removeCallbacks(hideSplashRunnable)
        splashHandler.postDelayed(hideSplashRunnable, SPLASH_DURATION_MS)
    }

    private fun showWaitingState() {
        updateBottomActionsVisibility(hasScannedAtLeastOnce)
        binding.waitingSection.visibility = View.VISIBLE
        binding.scannedSection.visibility = View.GONE
        binding.developerSection.visibility = View.GONE
        binding.statusText.text = "Waiting for scan..."
        binding.responseText.text = "-"
        binding.scrollContainer.post {
            binding.scrollContainer.scrollTo(0, 0)
        }
    }

    private fun showScannedState(barcode: String, productInfo: ProductInfo?) {
        hasScannedAtLeastOnce = true
        updateBottomActionsVisibility(true)
        binding.waitingSection.visibility = View.GONE
        binding.scannedSection.visibility = View.VISIBLE
        binding.developerSection.visibility = View.GONE
        binding.barcodeValueText.text = barcode
        binding.barcodeImage.setImageBitmap(renderBarcodeBitmap(barcode))
        binding.decodedInfoText.text = productInfo?.name ?: "Unknown product"
        binding.productSubtitleText.text = productInfo?.category
            ?: "This barcode is not part of the five-item MatNord demo catalog."
        binding.scrollContainer.post {
            binding.scrollContainer.scrollTo(0, 0)
        }
    }

    private fun clearPendingScanUi() {
        scanFinalizeHandler.removeCallbacks(finalizeScanRunnable)
        pendingScans.clear()
        lastQueuedBarcode = ""
        binding.barcodeValueText.text = "-"
        binding.barcodeImage.setImageBitmap(null)
        binding.decodedInfoText.text = "-"
        binding.productSubtitleText.text = "-"
        binding.responseText.text = "-"
        binding.statusText.text = "Waiting for scan..."
        binding.scannerSink.text?.clear()
        focusScannerSink()
    }

    private fun renderBarcodeBitmap(value: String): Bitmap {
        val matrix = MultiFormatWriter().encode(
            value,
            BarcodeFormat.CODE_128,
            BARCODE_WIDTH_PX,
            BARCODE_HEIGHT_PX
        )
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun setupScannerSink() {
        binding.scannerSink.showSoftInputOnFocus = false
        binding.scannerSink.isFocusableInTouchMode = true

        binding.scannerSink.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                consumeSinkTextAndScan()
                true
            } else {
                false
            }
        }

        binding.scannerSink.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB)
            ) {
                consumeSinkTextAndScan()
                true
            } else {
                false
            }
        }

        binding.scannerSink.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val max = MAX_WEDGE_BUFFER
                if ((s?.length ?: 0) > max) {
                    s?.delete(0, (s.length - max))
                }

                scanFinalizeHandler.removeCallbacks(finalizeScanRunnable)
                if (!s.isNullOrBlank()) {
                    // Some scanners do not send an Enter suffix. Finalize scan after brief idle time.
                    scanFinalizeHandler.postDelayed(finalizeScanRunnable, SCAN_IDLE_FINALIZE_MS)
                }
            }
        })
    }

    private fun consumeSinkTextAndScan() {
        scanFinalizeHandler.removeCallbacks(finalizeScanRunnable)
        val scanned = normalizeRawScan(binding.scannerSink.text?.toString().orEmpty())
        binding.scannerSink.text?.clear()
        if (scanned.isNotEmpty()) {
            handleScan(scanned)
        }
        focusScannerSink()
    }

    private fun normalizeRawScan(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // Keep the latest product-like token if multiple scans were concatenated.
        val productMatches = PRODUCT_ID_REGEX.findAll(trimmed).toList()
        if (productMatches.isNotEmpty()) {
            return productMatches.last().value
        }

        val tokens = trimmed.split(Regex("\\s+"))
        return tokens.lastOrNull().orEmpty()
    }

    private fun focusScannerSink() {
        binding.scannerSink.requestFocus()
        forceHideKeyboard()
    }

    private fun forceHideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    private fun updateBottomActionsVisibility(visible: Boolean) {
        binding.bottomActionsBar.visibility = if (visible) View.VISIBLE else View.GONE

        val params = binding.scrollContainer.layoutParams as FrameLayout.LayoutParams
        val targetBottomMargin = if (visible) dpToPx(96) else 0
        if (params.bottomMargin != targetBottomMargin) {
            params.bottomMargin = targetBottomMargin
            binding.scrollContainer.layoutParams = params
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
        val barcode = normalizeRawScan(rawValue)
        if (barcode.isEmpty()) {
            return
        }

        val productInfo = productCatalog[barcode]
        showScannedState(barcode, productInfo)

        enqueueScan(barcode)
    }

    private fun enqueueScan(barcode: String) {
        val now = SystemClock.elapsedRealtime()

        // Prevent accidental duplicate fire from scanner suffix/key echo.
        if (barcode == lastQueuedBarcode && now - lastQueuedAtMs < DUPLICATE_GUARD_MS) {
            return
        }

        lastQueuedBarcode = barcode
        lastQueuedAtMs = now
        pendingScans.addLast(barcode)
        drainScanQueue()
    }

    private fun drainScanQueue() {
        if (isSending) return

        val nextBarcode = pendingScans.removeFirstOrNull() ?: return
        isSending = true
        postScanToApi(nextBarcode)
    }

    private fun postScanToApi(productId: String) {
        binding.statusText.text = "Sending to API: $productId"

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
                    isSending = false
                    drainScanQueue()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyText = response.body?.string().orEmpty()
                runOnUiThread {
                    binding.responseText.text = if (bodyText.isBlank()) "(empty body)" else bodyText

                    if (response.isSuccessful) {
                        binding.statusText.text = "Sent successfully (${response.code})"
                    } else {
                        val apiError = parseErrorBody(bodyText)
                        binding.statusText.text = "API error ${response.code}: $apiError"
                    }

                    isSending = false
                    drainScanQueue()
                }
            }
        })
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
        private const val MAX_WEDGE_BUFFER = 128
        private const val SCAN_IDLE_FINALIZE_MS = 180L
        private const val DUPLICATE_GUARD_MS = 350L
        private const val SPLASH_DURATION_MS = 2500L
        private const val BARCODE_WIDTH_PX = 1200
        private const val BARCODE_HEIGHT_PX = 180
        private val PRODUCT_ID_REGEX = Regex("PROD-[A-Z]{3}-\\d{4}")
    }

    private data class ProductInfo(
        val name: String,
        val category: String
    )
}

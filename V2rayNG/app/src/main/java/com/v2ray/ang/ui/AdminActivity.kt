package com.v2ray.ang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAdminBinding
import com.v2ray.ang.util.DeviceLockCrypto
import org.json.JSONObject

class AdminActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityAdminBinding.inflate(layoutInflater) }

    private enum class TimeUnit(val labelResId: Int, val millis: Long) {
        HOURS(R.string.admin_unit_hours, 3_600_000L),
        DAYS(R.string.admin_unit_days, 86_400_000L),
        WEEKS(R.string.admin_unit_weeks, 604_800_000L),
        MONTHS(R.string.admin_unit_months, 2_592_000_000L) // 30 days
    }

    private var selectedUnit = TimeUnit.DAYS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true,
            title = getString(R.string.admin_panel_title))

        setupExpirationFields()
        binding.btnScanQr.setOnClickListener { scanQrForConfig() }
        binding.btnPasteClipboard.setOnClickListener { pasteFromClipboard() }
        binding.btnGenerate.setOnClickListener { generateLink() }
        binding.btnShare.setOnClickListener { shareLink() }
        binding.btnCopyLink.setOnClickListener { copyLink() }
        binding.btnClear.setOnClickListener { clearFields() }
    }

    private fun setupExpirationFields() {
        // Unit spinner
        val units = TimeUnit.values()
        val labels = units.map { getString(it.labelResId) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.spinnerExpirationUnit.setAdapter(adapter)
        binding.spinnerExpirationUnit.setText(getString(TimeUnit.DAYS.labelResId), false)

        binding.spinnerExpirationUnit.setOnItemClickListener { _, _, position, _ ->
            selectedUnit = units[position]
        }

        // Default amount
        binding.etExpirationAmount.setText("30")

        // Checkbox: sin expiración
        binding.cbNoExpiration.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutExpirationFields.alpha = if (isChecked) 0.4f else 1f
            binding.etExpirationAmount.isEnabled = !isChecked
            binding.spinnerExpirationUnit.isEnabled = !isChecked
        }
    }

    private fun calculateExpirationMs(): Long {
        if (binding.cbNoExpiration.isChecked) return 0L

        val amount = binding.etExpirationAmount.text.toString().toLongOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, R.string.admin_error_invalid_amount, Toast.LENGTH_SHORT).show()
            return -1L // signal invalid
        }

        return System.currentTimeMillis() + (amount * selectedUnit.millis)
    }

    private fun scanQrForConfig() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                val current = binding.etConfigLink.text.toString()
                if (current.isBlank()) {
                    binding.etConfigLink.setText(scanResult)
                } else {
                    binding.etConfigLink.setText("$current\n$scanResult")
                }
                Toast.makeText(this, R.string.admin_qr_scanned, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString().orEmpty()
            if (text.isNotBlank()) {
                val current = binding.etConfigLink.text.toString()
                if (current.isBlank()) {
                    binding.etConfigLink.setText(text)
                } else {
                    binding.etConfigLink.setText("$current\n$text")
                }
                Toast.makeText(this, R.string.admin_pasted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateLink() {
        val configText = binding.etConfigLink.text.toString().trim()
        val targetId = binding.etTargetDeviceId.text.toString().trim().uppercase()

        if (configText.isBlank()) {
            Toast.makeText(this, R.string.admin_error_empty_config, Toast.LENGTH_SHORT).show()
            return
        }
        if (targetId.length != 14 || !targetId.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}"))) {
            Toast.makeText(this, R.string.admin_error_invalid_device_id, Toast.LENGTH_SHORT).show()
            return
        }

        val expiresMs = calculateExpirationMs()
        if (expiresMs == -1L) return // validation failed

        val lines = configText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val generatedLinks = mutableListOf<String>()

        for (line in lines) {
            val payload = if (expiresMs > 0) {
                val envelope = JSONObject()
                envelope.put("l", line)
                envelope.put("e", expiresMs)
                envelope.toString()
            } else {
                line
            }
            val encrypted = DeviceLockCrypto.encrypt(payload, targetId)
            if (encrypted != null) {
                generatedLinks.add("cyberguard://import?data=$encrypted")
            }
        }

        if (generatedLinks.isEmpty()) {
            Toast.makeText(this, R.string.admin_error_encryption_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val failedCount = lines.size - generatedLinks.size
        if (failedCount > 0) {
            Toast.makeText(this, getString(R.string.admin_partial_encryption, failedCount), Toast.LENGTH_LONG).show()
        }

        val resultText = generatedLinks.joinToString("\n")
        binding.tvGeneratedLink.text = resultText
        binding.layoutResult.visibility = View.VISIBLE

        if (generatedLinks.size == 1) {
            binding.ivQrCode.setImageBitmap(generateQrCode(generatedLinks[0]))
            binding.ivQrCode.visibility = View.VISIBLE
            binding.tvBatchInfo.visibility = View.GONE
        } else {
            binding.ivQrCode.visibility = View.GONE
            binding.tvBatchInfo.text = getString(R.string.admin_batch_count, generatedLinks.size)
            binding.tvBatchInfo.visibility = View.VISIBLE
        }
    }

    private fun generateQrCode(content: String): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun shareLink() {
        val link = binding.tvGeneratedLink.text.toString()
        if (link.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.admin_share_via)))
    }

    private fun copyLink() {
        val link = binding.tvGeneratedLink.text.toString()
        if (link.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cyberguard_link", link))
        Toast.makeText(this, R.string.admin_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun clearFields() {
        binding.etConfigLink.setText("")
        binding.etTargetDeviceId.setText("")
        binding.cbNoExpiration.isChecked = false
        binding.etExpirationAmount.setText("30")
        binding.spinnerExpirationUnit.setText(getString(TimeUnit.DAYS.labelResId), false)
        selectedUnit = TimeUnit.DAYS
        binding.tvGeneratedLink.text = ""
        binding.layoutResult.visibility = View.GONE
    }
}

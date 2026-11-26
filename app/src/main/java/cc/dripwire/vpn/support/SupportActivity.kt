package cc.dripwire.vpn.support

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cc.dripwire.vpn.R
import cc.dripwire.vpn.api.AppApiClient
import cc.dripwire.vpn.databinding.ActivitySupportBinding
import cc.dripwire.vpn.license.DeviceIdentity
import kotlin.concurrent.thread

/** A simple text+photo chat thread with the admin, tied to this device's key+deviceId. */
class SupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportBinding
    private lateinit var adapter: SupportAdapter
    private var key: String = ""
    private val deviceId by lazy { DeviceIdentity.get(this) }
    private val deviceName by lazy { DeviceIdentity.displayName() }
    private var lastSeenId: Long = 0L
    private var pendingImageUri: Uri? = null
    private var sending = false

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                pendingImageUri = uri
                binding.attachPhotoButton.text = "📷✓"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.attachPhotoButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.sendButton.setOnClickListener { sendMessage() }

        key = getSharedPreferences("dripwire", MODE_PRIVATE).getString(PREF_LAST_KEY, "").orEmpty()
        if (key.isBlank()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.chatRecyclerView.visibility = View.GONE
            binding.inputBar.visibility = View.GONE
            return
        }

        adapter = SupportAdapter(mutableListOf())
        binding.chatRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.chatRecyclerView.adapter = adapter

        loadInitial()
    }

    override fun onResume() {
        super.onResume()
        if (key.isNotBlank()) pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onPause() {
        pollHandler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    private fun loadInitial() {
        thread {
            val result = AppApiClient.getSupportMessages(key, deviceId, sinceId = 0L)
            runOnUiThread {
                val messages = parseMessages(result)
                if (messages != null) {
                    adapter.replaceAll(messages)
                    lastSeenId = messages.maxOfOrNull { it.id } ?: 0L
                    scrollToBottom()
                }
            }
        }
    }

    private fun poll() {
        if (key.isBlank()) return
        thread {
            val result = AppApiClient.getSupportMessages(key, deviceId, sinceId = lastSeenId)
            runOnUiThread {
                val messages = parseMessages(result)
                if (!messages.isNullOrEmpty()) {
                    adapter.appendAll(messages)
                    lastSeenId = messages.maxOfOrNull { it.id } ?: lastSeenId
                    scrollToBottom()
                }
            }
        }
    }

    private fun parseMessages(result: AppApiClient.ApiResult): List<SupportMessage>? {
        if (result !is AppApiClient.ApiResult.Success || !result.json.optBoolean("ok")) return null
        val array = result.json.optJSONArray("messages") ?: return emptyList()
        val list = mutableListOf<SupportMessage>()
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            list.add(
                SupportMessage(
                    id = m.optLong("id"),
                    sender = m.optString("sender", "admin"),
                    body = m.optString("body").takeIf { it.isNotBlank() },
                    imageUrl = m.optString("imageUrl").takeIf { it.isNotBlank() },
                    createdAt = m.optString("createdAt"),
                ),
            )
        }
        return list
    }

    private fun sendMessage() {
        if (sending) return
        val text = binding.messageInput.text?.toString()?.trim().orEmpty()
        val imageUri = pendingImageUri

        if (text.isBlank() && imageUri == null) {
            Toast.makeText(this, R.string.err_message_empty, Toast.LENGTH_SHORT).show()
            return
        }

        sending = true
        binding.sendButton.isEnabled = false
        thread {
            var imageId: Long? = null
            if (imageUri != null) {
                val prepared = ImageUtils.prepareForUpload(this, imageUri)
                if (prepared == null) {
                    runOnUiThread {
                        sending = false
                        binding.sendButton.isEnabled = true
                        Toast.makeText(this, R.string.err_image_invalid, Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                when (val uploadResult = AppApiClient.uploadSupportImage(key, deviceId, prepared.base64, prepared.mime, prepared.filename)) {
                    is AppApiClient.ApiResult.Success -> {
                        if (uploadResult.json.optBoolean("ok")) {
                            imageId = uploadResult.json.optLong("imageId")
                        } else {
                            runOnUiThread {
                                sending = false
                                binding.sendButton.isEnabled = true
                                Toast.makeText(this, errorMessageFor(uploadResult.json.optString("error")), Toast.LENGTH_LONG).show()
                            }
                            return@thread
                        }
                    }
                    is AppApiClient.ApiResult.NetworkError -> {
                        runOnUiThread {
                            sending = false
                            binding.sendButton.isEnabled = true
                            Toast.makeText(this, R.string.err_network, Toast.LENGTH_LONG).show()
                        }
                        return@thread
                    }
                }
            }

            val sendResult = AppApiClient.sendSupportMessage(key, deviceId, deviceName, text.ifBlank { null }, imageId)
            runOnUiThread {
                sending = false
                binding.sendButton.isEnabled = true
                when (sendResult) {
                    is AppApiClient.ApiResult.Success -> {
                        if (sendResult.json.optBoolean("ok")) {
                            binding.messageInput.setText("")
                            pendingImageUri = null
                            binding.attachPhotoButton.text = "📷"
                            poll()
                        } else {
                            Toast.makeText(this, errorMessageFor(sendResult.json.optString("error")), Toast.LENGTH_LONG).show()
                        }
                    }
                    is AppApiClient.ApiResult.NetworkError ->
                        Toast.makeText(this, R.string.err_network, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.chatRecyclerView.scrollToPosition(count - 1)
    }

    private fun errorMessageFor(code: String): String = when (code) {
        "empty_message", "missing_image" -> getString(R.string.err_message_empty)
        "image_too_large", "file_too_large" -> getString(R.string.err_image_too_large)
        "invalid_image", "unsupported_media_type" -> getString(R.string.err_image_invalid)
        "missing_device_id" -> getString(R.string.err_missing_device_id)
        else -> getString(R.string.err_unknown, code)
    }

    companion object {
        private const val PREF_LAST_KEY = "last_license_key"
        private const val POLL_INTERVAL_MS = 6_000L
    }
}

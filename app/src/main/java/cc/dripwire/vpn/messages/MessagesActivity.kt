package cc.dripwire.vpn.messages

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cc.dripwire.vpn.R
import cc.dripwire.vpn.api.AppApiClient
import cc.dripwire.vpn.api.optStringOrNull
import cc.dripwire.vpn.databinding.ActivityMessagesBinding
import cc.dripwire.vpn.license.DeviceIdentity
import kotlin.concurrent.thread

/** Broadcast + personal messages from the admin, addressed to this device's key+deviceId. */
class MessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessagesBinding
    private var adapter: MessageAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val key = getSharedPreferences("dripwire", MODE_PRIVATE).getString(PREF_LAST_KEY, "").orEmpty()
        if (key.isBlank()) {
            showEmpty(getString(R.string.messages_empty_no_key))
            return
        }

        loadMessages(key)
    }

    private fun loadMessages(key: String) {
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        binding.messagesRecyclerView.visibility = View.GONE

        val deviceId = DeviceIdentity.get(this)
        thread {
            val result = AppApiClient.getMessages(key, deviceId)
            runOnUiThread { onMessagesLoaded(result, key, deviceId) }
        }
    }

    private fun onMessagesLoaded(result: AppApiClient.ApiResult, key: String, deviceId: String) {
        binding.loadingSpinner.visibility = View.GONE

        if (result !is AppApiClient.ApiResult.Success || !result.json.optBoolean("ok")) {
            showEmpty(getString(R.string.err_network))
            return
        }

        val array = result.json.optJSONArray("messages")
        val messages = mutableListOf<AppMessage>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: continue
                messages.add(
                    AppMessage(
                        id = m.optLong("id"),
                        body = m.optStringOrNull("body") ?: "",
                        createdAt = m.optStringOrNull("createdAt") ?: "",
                        read = m.optBoolean("read", false),
                    ),
                )
            }
        }

        if (messages.isEmpty()) {
            showEmpty(getString(R.string.messages_empty))
            return
        }

        val newestFirst = messages.sortedByDescending { it.id }.toMutableList()
        adapter = MessageAdapter(newestFirst) { message -> onMessageClicked(message, key, deviceId) }
        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.messagesRecyclerView.adapter = adapter
        binding.messagesRecyclerView.visibility = View.VISIBLE
    }

    private fun onMessageClicked(message: AppMessage, key: String, deviceId: String) {
        if (message.read) return
        adapter?.markRead(message.id)
        thread { AppApiClient.markMessageRead(message.id, key, deviceId) }
    }

    private fun showEmpty(text: String) {
        binding.loadingSpinner.visibility = View.GONE
        binding.messagesRecyclerView.visibility = View.GONE
        binding.emptyText.text = text
        binding.emptyText.visibility = View.VISIBLE
    }

    companion object {
        private const val PREF_LAST_KEY = "last_license_key"
    }
}

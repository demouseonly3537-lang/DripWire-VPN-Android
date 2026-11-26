package cc.dripwire.vpn.gate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cc.dripwire.vpn.MainActivity
import cc.dripwire.vpn.R
import cc.dripwire.vpn.api.AppApiClient
import cc.dripwire.vpn.databinding.ActivityChannelGateBinding
import kotlin.concurrent.thread

/**
 * Shown once per app process, before the key-entry screen: the admin's Telegram/WhatsApp
 * channel links, with "Join" buttons and a "Continue" button. This is a trust-based prompt
 * only — there's no real API to verify someone actually joined a channel without much heavier
 * integration (a Telegram bot + login widget), so tapping Continue always lets the user
 * proceed. If the admin hasn't configured any links yet (or the backend can't be reached),
 * this screen is skipped entirely rather than blocking the user on nothing.
 */
class ChannelGateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelGateBinding
    private var telegramLink: String? = null
    private var whatsappLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.continueButton.setOnClickListener { proceedToMain() }
        binding.telegramButton.setOnClickListener { openLink(telegramLink) }
        binding.whatsappButton.setOnClickListener { openLink(whatsappLink) }

        thread {
            val result = AppApiClient.getChannelLinks()
            runOnUiThread { onLinksFetched(result) }
        }
    }

    private fun onLinksFetched(result: AppApiClient.ApiResult) {
        val (telegram, whatsapp) = when (result) {
            is AppApiClient.ApiResult.Success -> {
                val json = result.json
                if (!json.optBoolean("ok")) {
                    null to null
                } else {
                    json.optString("telegram").trim() to json.optString("whatsapp").trim()
                }
            }
            is AppApiClient.ApiResult.NetworkError -> null to null
        }

        telegramLink = telegram?.takeIf { it.isNotBlank() }
        whatsappLink = whatsapp?.takeIf { it.isNotBlank() }

        if (telegramLink == null && whatsappLink == null) {
            // Nothing configured (or couldn't fetch) — don't block the user on an empty prompt.
            proceedToMain()
            return
        }

        binding.gateLoadingSpinner.visibility = View.GONE
        binding.gateLinksGroup.visibility = View.VISIBLE
        binding.continueButton.visibility = View.VISIBLE
        binding.telegramButton.visibility = if (telegramLink != null) View.VISIBLE else View.GONE
        binding.whatsappButton.visibility = if (whatsappLink != null) View.VISIBLE else View.GONE
    }

    private fun openLink(link: String?) {
        if (link.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.err_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun proceedToMain() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

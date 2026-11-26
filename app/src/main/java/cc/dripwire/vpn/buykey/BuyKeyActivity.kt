package cc.dripwire.vpn.buykey

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cc.dripwire.vpn.R
import cc.dripwire.vpn.api.AppApiClient
import cc.dripwire.vpn.api.optStringOrNull
import cc.dripwire.vpn.databinding.ActivityBuyKeyBinding
import cc.dripwire.vpn.license.DeviceIdentity
import cc.dripwire.vpn.license.LicenseClient
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Self-serve key purchase: pick a plan -> pay the generated UPI QR for the exact amount -> type
 * in the UTR -> poll until the admin's backend (automatically, via Gmail parsing, or manually)
 * marks the order paid -> the new key is handed straight back to MainActivity.
 *
 * Only one pending order is tracked at a time (this device's last unpaid order), persisted
 * locally so the user can close the app mid-payment and come back to the same order later
 * instead of starting over or accidentally creating a second order for the same purchase.
 */
class BuyKeyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuyKeyBinding
    private lateinit var prefs: android.content.SharedPreferences
    private val pollHandler = Handler(Looper.getMainLooper())
    private var currentOrderId: Long? = null
    private var polling = false

    private val deviceId by lazy { DeviceIdentity.get(this) }
    private val deviceName by lazy { DeviceIdentity.displayName() }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val orderId = currentOrderId
            if (orderId != null) checkOrderStatus(orderId, isPoll = true)
            if (polling) pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("rakshakx_buykey", MODE_PRIVATE)

        binding.backButton.setOnClickListener { finish() }
        binding.payWithUpiButton.setOnClickListener { openUpiApp() }
        binding.submitUtrButton.setOnClickListener { submitUtr() }

        binding.qrWebView.settings.javaScriptEnabled = false

        val pendingOrderId = prefs.getLong(PREF_PENDING_ORDER_ID, -1L).takeIf { it > 0 }
        if (pendingOrderId != null) {
            currentOrderId = pendingOrderId
            showLoading()
            checkOrderStatus(pendingOrderId, isPoll = false, fallbackToPlansOnError = true)
        } else {
            loadPlans()
        }
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (currentOrderId != null && binding.orderContainer.visibility == View.VISIBLE) {
            startPolling()
        }
    }

    // ---------- Step 1: plans ----------

    private fun loadPlans() {
        showLoading()
        thread {
            val result = AppApiClient.getPlans()
            runOnUiThread { onPlansLoaded(result) }
        }
    }

    private fun onPlansLoaded(result: AppApiClient.ApiResult) {
        if (result !is AppApiClient.ApiResult.Success || !result.json.optBoolean("ok")) {
            showUnavailable()
            return
        }
        val json = result.json
        val upiActive = json.optJSONObject("upi")?.optBoolean("isActive", false) ?: false
        val plansArray = json.optJSONArray("plans")
        val plans = mutableListOf<Plan>()
        if (plansArray != null) {
            for (i in 0 until plansArray.length()) {
                val p = plansArray.optJSONObject(i) ?: continue
                plans.add(
                    Plan(
                        id = p.optLong("id"),
                        name = p.optStringOrNull("name") ?: "Plan",
                        priceInr = p.optDouble("priceInr", 0.0),
                    ),
                )
            }
        }
        if (!upiActive || plans.isEmpty()) {
            showUnavailable()
            return
        }
        showPlansList(plans)
    }

    private fun showPlansList(plans: List<Plan>) {
        binding.plansRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.plansRecyclerView.adapter = PlanAdapter(plans) { plan -> selectPlan(plan) }

        binding.loadingSpinner.visibility = View.GONE
        binding.unavailableText.visibility = View.GONE
        binding.orderContainer.visibility = View.GONE
        binding.plansContainer.visibility = View.VISIBLE
    }

    private fun selectPlan(plan: Plan) {
        showLoading()
        thread {
            val result = AppApiClient.createOrder(plan.id, deviceId, deviceName)
            runOnUiThread { onOrderCreated(result) }
        }
    }

    private fun onOrderCreated(result: AppApiClient.ApiResult) {
        when (result) {
            is AppApiClient.ApiResult.Success -> {
                val json = result.json
                if (json.optBoolean("ok")) {
                    // Not persisted as "pending" yet — only once the user actually submits a
                    // UTR (below) do we commit to resuming this exact order on reopen. Until
                    // then, qrSvg/upiLink are only returned by this create-order response (the
                    // status endpoint doesn't repeat them), so if we persisted now and the user
                    // left before paying, reopening would show "verifying" with no way to
                    // actually pay — picking the plan again instead is the safer fallback.
                    currentOrderId = json.optLong("orderId")
                    showOrderPayState(json)
                } else {
                    Toast.makeText(this, errorMessageFor(json.optString("error")), Toast.LENGTH_LONG).show()
                    loadPlans()
                }
            }
            is AppApiClient.ApiResult.NetworkError -> {
                Toast.makeText(this, R.string.err_network, Toast.LENGTH_LONG).show()
                loadPlans()
            }
        }
    }

    // ---------- Step 2: pay + submit UTR ----------

    private fun showOrderPayState(order: JSONObject) {
        binding.loadingSpinner.visibility = View.GONE
        binding.unavailableText.visibility = View.GONE
        binding.plansContainer.visibility = View.GONE
        binding.orderContainer.visibility = View.VISIBLE
        hideOrderError()

        val amountPaise = order.optLong("amountPaise", 0L)
        binding.amountText.text = formatPaiseAsRupees(amountPaise)

        val qrSvg = order.optStringOrNull("qrSvg")
        if (!qrSvg.isNullOrBlank()) {
            val html = "<html><body style=\"margin:0;padding:0;\">$qrSvg</body></html>"
            binding.qrWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        upiPayLink = order.optStringOrNull("upiLink")
        binding.orderStatusSection.visibility = View.GONE

        startPolling()
    }

    private var upiPayLink: String? = null

    private fun openUpiApp() {
        val link = upiPayLink
        if (link.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.err_no_upi_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun submitUtr() {
        val orderId = currentOrderId ?: return
        val utr = binding.utrInput.text?.toString()?.trim().orEmpty()
        if (utr.isBlank()) {
            showOrderError(getString(R.string.err_empty_utr))
            return
        }
        hideOrderError()
        binding.submitUtrButton.isEnabled = false
        thread {
            val result = AppApiClient.submitUtr(orderId, deviceId, utr)
            runOnUiThread {
                binding.submitUtrButton.isEnabled = true
                onUtrSubmitted(result)
            }
        }
    }

    private fun onUtrSubmitted(result: AppApiClient.ApiResult) {
        when (result) {
            is AppApiClient.ApiResult.Success -> {
                val json = result.json
                if (json.optBoolean("ok")) {
                    // Now it's worth persisting — the user has committed to this order by
                    // submitting a UTR, so reopening the screen later should resume verifying
                    // it rather than starting a fresh purchase.
                    currentOrderId?.let { prefs.edit().putLong(PREF_PENDING_ORDER_ID, it).apply() }
                    showPendingStatus()
                    startPolling()
                } else {
                    showOrderError(errorMessageFor(json.optString("error")))
                }
            }
            is AppApiClient.ApiResult.NetworkError -> showOrderError(getString(R.string.err_network))
        }
    }

    private fun showPendingStatus() {
        binding.orderStatusSection.visibility = View.VISIBLE
        binding.orderStatusTitleText.setText(R.string.order_pending_title)
        binding.orderStatusSubtitleText.setText(R.string.order_pending_subtitle)
    }

    // ---------- Polling ----------

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun checkOrderStatus(orderId: Long, isPoll: Boolean, fallbackToPlansOnError: Boolean = false) {
        thread {
            val result = AppApiClient.getOrderStatus(orderId, deviceId)
            runOnUiThread { onOrderStatus(result, isPoll, fallbackToPlansOnError) }
        }
    }

    private fun onOrderStatus(
        result: AppApiClient.ApiResult,
        isPoll: Boolean,
        fallbackToPlansOnError: Boolean,
    ) {
        when (result) {
            is AppApiClient.ApiResult.Success -> {
                val json = result.json
                if (!json.optBoolean("ok")) {
                    if (fallbackToPlansOnError) {
                        clearPendingOrder()
                        loadPlans()
                    } else if (!isPoll) {
                        showOrderError(errorMessageFor(json.optString("error")))
                    }
                    return
                }
                when (json.optString("status")) {
                    "paid" -> {
                        stopPolling()
                        val keyCode = json.optString("keyCode")
                        activateAndFinish(keyCode)
                    }
                    else -> if (!isPoll) {
                        // Resuming a previously-created order — show the pay/UTR screen again
                        // (re-fetch its amount/QR via a fresh order-create-less path isn't
                        // available, so we only have what submit-utr's own fields would give;
                        // the order-status endpoint still has amountPaise/orderCode, enough to
                        // show the amount while the user can re-check or wait).
                        showResumedPendingState(json)
                    }
                }
            }
            is AppApiClient.ApiResult.NetworkError -> {
                // A transient network hiccup while polling shouldn't interrupt the user —
                // just try again on the next poll tick.
            }
        }
    }

    private fun showResumedPendingState(order: JSONObject) {
        binding.loadingSpinner.visibility = View.GONE
        binding.unavailableText.visibility = View.GONE
        binding.plansContainer.visibility = View.GONE
        binding.orderContainer.visibility = View.VISIBLE
        hideOrderError()

        val amountPaise = order.optLong("amountPaise", 0L)
        binding.amountText.text = formatPaiseAsRupees(amountPaise)
        // No fresh qrSvg/upiLink is returned by the status endpoint — hide the pay section and
        // just show the verifying state; the user already paid once if they got this far.
        binding.qrSection.visibility = View.GONE
        showPendingStatus()
        startPolling()
    }

    private fun activateAndFinish(keyCode: String) {
        binding.orderStatusSection.visibility = View.VISIBLE
        binding.orderStatusTitleText.setText(R.string.order_paid_title)
        binding.orderStatusSubtitleText.setText(R.string.order_paid_subtitle)
        clearPendingOrder()

        thread {
            val result = LicenseClient.activate(keyCode, deviceId, deviceName)
            runOnUiThread {
                val data = Intent().putExtra(EXTRA_KEY, keyCode)
                if (result is LicenseClient.ActivateResult.Success) {
                    data.putExtra(EXTRA_ACTIVATED, true)
                        .putExtra(EXTRA_CONFIG, result.config)
                        .putExtra(EXTRA_EXPIRES_AT, result.expiresAt ?: -1L)
                        .putExtra(EXTRA_SERVER_TIME, result.serverTime)
                        .putExtra(EXTRA_DEVICE_LABEL, result.deviceLabel)
                } else {
                    data.putExtra(EXTRA_ACTIVATED, false)
                }
                setResult(RESULT_OK, data)
                finish()
            }
        }
    }

    private fun clearPendingOrder() {
        currentOrderId = null
        prefs.edit().remove(PREF_PENDING_ORDER_ID).apply()
    }

    // ---------- Shared view states ----------

    private fun showLoading() {
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.unavailableText.visibility = View.GONE
        binding.plansContainer.visibility = View.GONE
        binding.orderContainer.visibility = View.GONE
    }

    private fun showUnavailable() {
        binding.loadingSpinner.visibility = View.GONE
        binding.unavailableText.visibility = View.VISIBLE
        binding.plansContainer.visibility = View.GONE
        binding.orderContainer.visibility = View.GONE
    }

    private fun showOrderError(message: String) {
        binding.orderErrorText.text = message
        binding.orderErrorText.visibility = View.VISIBLE
    }

    private fun hideOrderError() {
        binding.orderErrorText.visibility = View.GONE
    }

    private fun errorMessageFor(code: String): String = when (code) {
        "invalid_plan" -> getString(R.string.err_invalid_plan)
        "upi_not_configured" -> getString(R.string.err_upi_not_configured)
        "order_not_found" -> getString(R.string.err_order_not_found)
        "forbidden" -> getString(R.string.err_forbidden)
        "already_paid" -> getString(R.string.err_already_paid)
        "invalid_utr" -> getString(R.string.err_invalid_utr)
        "duplicate_utr" -> getString(R.string.err_duplicate_utr)
        "missing_device_id" -> getString(R.string.err_missing_device_id)
        else -> getString(R.string.err_unknown, code)
    }

    companion object {
        private const val PREF_PENDING_ORDER_ID = "pending_order_id"
        private const val POLL_INTERVAL_MS = 7_000L

        const val EXTRA_KEY = "extra_key"
        const val EXTRA_ACTIVATED = "extra_activated"
        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_EXPIRES_AT = "extra_expires_at"
        const val EXTRA_SERVER_TIME = "extra_server_time"
        const val EXTRA_DEVICE_LABEL = "extra_device_label"
    }
}

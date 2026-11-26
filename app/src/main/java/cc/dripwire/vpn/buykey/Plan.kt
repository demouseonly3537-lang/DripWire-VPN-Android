package cc.dripwire.vpn.buykey

import java.util.Locale

data class Plan(
    val id: Long,
    val name: String,
    val priceInr: Double,
) {
    /** "₹99" for a whole-rupee price, "₹49.50" when it isn't. */
    fun priceDisplay(): String = if (priceInr == Math.floor(priceInr)) {
        "₹${priceInr.toLong()}"
    } else {
        String.format(Locale.US, "₹%.2f", priceInr)
    }
}

/** Formats paise (the UPI order amount, as an integer) as a rupee string, e.g. 9900 -> "₹99.00". */
fun formatPaiseAsRupees(amountPaise: Long): String =
    String.format(Locale.US, "₹%.2f", amountPaise / 100.0)

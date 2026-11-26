package cc.dripwire.vpn.messages

data class AppMessage(
    val id: Long,
    val body: String,
    val createdAt: String,
    var read: Boolean,
)

/** "2026-12-25T10:00:00.000Z" -> "2026-12-25 10:00" — no java.time dependency needed (this app's
 * minSdk is 24, below java.time's API 26 without extra desugaring setup). */
fun formatIsoTimestamp(iso: String): String {
    val withSpace = iso.replace('T', ' ')
    return if (withSpace.length >= 16) withSpace.substring(0, 16) else withSpace
}

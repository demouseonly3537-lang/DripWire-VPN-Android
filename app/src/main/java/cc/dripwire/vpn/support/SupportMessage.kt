package cc.dripwire.vpn.support

data class SupportMessage(
    val id: Long,
    val sender: String, // "user" or "admin"
    val body: String?,
    val imageUrl: String?,
    val createdAt: String,
)

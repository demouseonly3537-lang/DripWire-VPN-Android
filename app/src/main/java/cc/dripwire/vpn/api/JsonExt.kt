package cc.dripwire.vpn.api

import org.json.JSONObject

/**
 * `JSONObject.optString(key)` has a well-known trap on Android: when the backend sends an
 * explicit JSON `null` for a field (very common for optional fields like a message's missing
 * `imageUrl`/`body`), Android's on-device org.json implementation returns the *literal 4-letter
 * string* `"null"` instead of an empty string — because it falls back to `String.valueOf()` on
 * the `JSONObject.NULL` sentinel, whose own `toString()` returns `"null"`. A naive
 * `.takeIf { it.isNotBlank() }` after `optString` does NOT catch this ("null" isn't blank), so
 * it would silently treat a genuinely-absent field as present — e.g. showing a photo
 * placeholder with no photo, or literally printing the word "null" as a chat message.
 *
 * This checks [JSONObject.isNull] (which correctly treats both a missing key and an explicit
 * JSON null as "absent") before ever calling optString, so a null/missing field always comes
 * back as a real Kotlin `null`.
 */
fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key, "").takeIf { it.isNotBlank() }
}

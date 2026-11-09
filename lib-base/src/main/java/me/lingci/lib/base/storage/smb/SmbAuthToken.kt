package me.lingci.lib.base.storage.smb

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object SmbAuthToken {

    private const val PREFIX = "smb:"

    data class Credentials(
        val username: String?,
        val password: String?,
        val domain: String?
    )

    fun encode(username: String?, password: String?, domain: String? = null): String {
        val credentials = normalize(username, password, domain)
        val json = buildJsonObject {
            put("username", credentials.username.orEmpty())
            put("password", credentials.password.orEmpty())
            put("domain", credentials.domain.orEmpty())
        }.toString()
        val payload = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return PREFIX + payload
    }

    fun decode(token: String?): Credentials? {
        val value = token?.trim()?.takeIf { it.startsWith(PREFIX) } ?: return null
        return try {
            val json = String(
                Base64.decode(value.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val obj = Json.parseToJsonElement(json).jsonObject
            Credentials(
                username = obj.optString("username").takeIf { it.isNotBlank() },
                password = obj.optString("password").takeIf { it.isNotBlank() },
                domain = obj.optString("domain").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 与原 org.json 的 optString 行为一致：缺失或 JSON null 均返回空串 */
    private fun JsonObject.optString(key: String): String {
        val element = this[key] as? JsonPrimitive ?: return ""
        return if (element is JsonNull) "" else element.content
    }

    fun normalize(username: String?, password: String?, domain: String? = null): Credentials {
        val normalizedPassword = password?.takeIf { it.isNotBlank() }
        val normalizedDomain = domain?.trim()?.takeIf { it.isNotBlank() }
        val rawUsername = username?.trim()?.takeIf { it.isNotBlank() }
            ?: return Credentials(null, normalizedPassword, normalizedDomain)
        if (normalizedDomain != null) {
            return Credentials(rawUsername, normalizedPassword, normalizedDomain)
        }

        val backslashIndex = rawUsername.indexOf('\\')
        if (backslashIndex > 0 && backslashIndex < rawUsername.lastIndex) {
            return Credentials(
                username = rawUsername.substring(backslashIndex + 1),
                password = normalizedPassword,
                domain = rawUsername.substring(0, backslashIndex)
            )
        }

        val semicolonIndex = rawUsername.indexOf(';')
        if (semicolonIndex > 0 && semicolonIndex < rawUsername.lastIndex) {
            return Credentials(
                username = rawUsername.substring(semicolonIndex + 1),
                password = normalizedPassword,
                domain = rawUsername.substring(0, semicolonIndex)
            )
        }

        return Credentials(rawUsername, normalizedPassword, null)
    }
}

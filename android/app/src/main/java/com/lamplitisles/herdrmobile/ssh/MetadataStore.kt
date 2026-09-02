package com.lamplitisles.herdrmobile.ssh

import android.content.SharedPreferences
import org.json.JSONObject

/** App-private non-secret metadata for the single Connection Target. */
class SharedPreferencesMetadataStore(private val preferences: SharedPreferences) : TargetRepository, HostTrustRepository {
    private val lock = Any()

    override fun get(): ConnectionTarget? = synchronized(lock) {
        val item = targetJson() ?: return@synchronized null
        val host = item.optString("host", "")
        val user = item.optString("user", "")
        val port = item.optInt("port", -1)
        if (host.isBlank() || user.isBlank() || port !in 1..65535) null else ConnectionTarget(host, port, user)
    }

    override fun save(target: ConnectionTarget) {
        synchronized(lock) {
            preferences.edit().putString(TARGET_KEY, JSONObject().apply {
                put("host", target.host)
                put("port", target.port)
                put("user", target.user)
            }.toString()).apply()
        }
    }

    override fun fingerprint(): String? = synchronized(lock) {
        trustJson().opt("fingerprint")?.takeIf { it is String } as? String
    }

    override fun save(fingerprint: String) {
        synchronized(lock) {
            // The trust decision gates the very next connection. Commit it
            // before returning so a process stop cannot lose explicit trust.
            preferences.edit().putString(TRUST_KEY, JSONObject().put("fingerprint", fingerprint).toString()).commit()
        }
    }

    override fun clear() {
        synchronized(lock) { preferences.edit().remove(TRUST_KEY).commit() }
    }

    private fun targetJson(): JSONObject? {
        val raw = preferences.getString(TARGET_KEY, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun trustJson(): JSONObject {
        val raw = preferences.getString(TRUST_KEY, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private companion object {
        const val TARGET_KEY = "connection_target"
        const val TRUST_KEY = "host_trust_record"
    }
}

package com.novastream.tv

import android.content.Context

class NetworkProtectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("novastream_network_protection", Context.MODE_PRIVATE)

    var dnsProfileId: String
        get() = prefs.getString("dns_profile", "system") ?: "system"
        set(value) {
            val safe = DnsProtectionEngine.profiles.firstOrNull { it.id == value }?.id ?: "system"
            prefs.edit().putString("dns_profile", safe).apply()
        }

    var strictAdFreeSources: Boolean
        get() = prefs.getBoolean("strict_ad_free_sources", true)
        set(value) = prefs.edit().putBoolean("strict_ad_free_sources", value).apply()

    var showProtectionStatus: Boolean
        get() = prefs.getBoolean("show_protection_status", true)
        set(value) = prefs.edit().putBoolean("show_protection_status", value).apply()

    val dnsProfile: DnsProfile
        get() = DnsProtectionEngine.byId(dnsProfileId)
}

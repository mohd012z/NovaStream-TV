package com.novastream.tv

/**
 * Describes DNS choices without changing Android's global Private DNS setting.
 * NovaStream can use this metadata for diagnostics and user guidance.
 */
enum class DnsCapability { RESOLVER, SECURITY, ADS_TRACKERS, FAMILY }

data class DnsProfile(
    val id: String,
    val name: String,
    val privateDnsHostname: String? = null,
    val ipv4: List<String> = emptyList(),
    val capabilities: Set<DnsCapability> = setOf(DnsCapability.RESOLVER),
    val description: String
) {
    val filtersAds: Boolean get() = DnsCapability.ADS_TRACKERS in capabilities
}

object DnsProtectionEngine {
    val profiles = listOf(
        DnsProfile(
            id = "system",
            name = "System / Automatic",
            description = "Use the Android/network DNS configuration."
        ),
        DnsProfile(
            id = "cloudflare",
            name = "Cloudflare 1.1.1.1",
            privateDnsHostname = "one.one.one.one",
            ipv4 = listOf("1.1.1.1", "1.0.0.1"),
            description = "Privacy-focused resolver; not classified as an ad blocker."
        ),
        DnsProfile(
            id = "cloudflare-security",
            name = "Cloudflare Security",
            privateDnsHostname = "security.cloudflare-dns.com",
            ipv4 = listOf("1.1.1.2", "1.0.0.2"),
            capabilities = setOf(DnsCapability.RESOLVER, DnsCapability.SECURITY),
            description = "Resolver with malware/security filtering."
        ),
        DnsProfile(
            id = "adguard-default",
            name = "AdGuard Default",
            privateDnsHostname = "dns.adguard-dns.com",
            capabilities = setOf(DnsCapability.RESOLVER, DnsCapability.ADS_TRACKERS),
            description = "DNS-level advertising and tracker filtering."
        ),
        DnsProfile(
            id = "adguard-family",
            name = "AdGuard Family",
            privateDnsHostname = "family.adguard-dns.com",
            capabilities = setOf(
                DnsCapability.RESOLVER,
                DnsCapability.ADS_TRACKERS,
                DnsCapability.FAMILY
            ),
            description = "Advertising/tracker filtering with family-oriented filtering."
        )
    )

    fun byId(id: String): DnsProfile =
        profiles.firstOrNull { it.id == id } ?: profiles.first()

    /**
     * DNS filtering cannot prove that a video is ad-free: same-origin or
     * server-side ads can be part of the media stream itself.
     */
    fun protectionSummary(profile: DnsProfile, source: OfficialDramaSource): String {
        val dns = when {
            profile.filtersAds -> "DNS ad/tracker filtering available"
            DnsCapability.SECURITY in profile.capabilities -> "Security DNS; not an ad blocker"
            else -> "DNS resolver; no ad filtering claim"
        }
        val sourceStatus = when (source.adPolicy) {
            AdPolicy.AD_FREE -> "source marked ad-free"
            AdPolicy.AD_SUPPORTED -> "source marked ad-supported"
            AdPolicy.UNKNOWN -> "stream ad status unknown"
        }
        return "$dns • $sourceStatus"
    }
}

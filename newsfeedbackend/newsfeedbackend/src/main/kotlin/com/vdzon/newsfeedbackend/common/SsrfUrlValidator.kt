package com.vdzon.newsfeedbackend.common

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF-hardening voor user-ingevoerde URLs (o.a. RSS-feed-URLs): staat
 * alleen http/https toe en wijst URLs af waarvan de host resolvet naar een
 * loopback-, link-local-, private- (RFC1918/ULA) of multicast-adres.
 * Gedeeld tussen `settings` en `rss` — geplaatst op het root-niveau van
 * `common` (zoals [Exceptions.kt]) zodat beide modules 'm mogen gebruiken
 * zonder de Spring Modulith-grenzen te schenden.
 */
object SsrfUrlValidator {

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * [resolveHost] is injecteerbaar zodat tests deterministisch IP-ranges
     * kunnen simuleren zonder echte DNS-lookups; productiecode gebruikt de
     * default (`InetAddress.getAllByName`), met een verse resolutie per call.
     */
    fun validate(
        url: String,
        resolveHost: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
        // Alleen true in de e2e-testomgeving (app.security.ssrf.allow-loopback), waar de
        // fake content-server per definitie op 127.0.0.1 draait. Nooit true in productie —
        // link-local/private/ULA/multicast blijven ook dan geblokkeerd. Laatste parameter (na
        // resolveHost) + altijd named doorgegeven vanuit callers, zodat bestaande
        // trailing-lambda call-sites voor resolveHost ongewijzigd blijven werken.
        allowLoopback: Boolean = false,
    ): ValidationResult {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return ValidationResult.Invalid("ongeldige URL")
        }

        val scheme = uri.scheme
        if (scheme == null || scheme.lowercase() !in ALLOWED_SCHEMES) {
            return ValidationResult.Invalid("alleen http/https-URLs zijn toegestaan")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return ValidationResult.Invalid("URL bevat geen host")
        }

        val addresses = try {
            resolveHost(host)
        } catch (e: Exception) {
            emptyList()
        }
        if (addresses.isEmpty()) {
            return ValidationResult.Invalid("host '$host' kan niet worden geresolved")
        }

        for (address in addresses) {
            val reason = blockedReasonFor(address, allowLoopback)
            if (reason != null) {
                return ValidationResult.Invalid(
                    "host '$host' resolvet naar een niet-toegestaan adres (${reason}: ${address.hostAddress})"
                )
            }
        }

        return ValidationResult.Valid
    }

    private fun blockedReasonFor(address: InetAddress, allowLoopback: Boolean): String? {
        val normalized = normalize(address)
        return when {
            normalized.isAnyLocalAddress -> "wildcard"
            normalized.isLoopbackAddress -> if (allowLoopback) null else "loopback"
            normalized.isLinkLocalAddress -> "link-local"
            normalized.isSiteLocalAddress -> "private"
            isIpv6UniqueLocal(normalized) -> "unique-local (ULA)"
            normalized.isMulticastAddress -> "multicast"
            else -> null
        }
    }

    /** Ontrafelt IPv4-mapped IPv6-adressen (`::ffff:a.b.c.d`) naar hun IPv4-equivalent. */
    private fun normalize(address: InetAddress): InetAddress {
        val bytes = address.address
        if (address is Inet6Address && bytes.size == 16 &&
            (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        ) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        return address
    }

    private fun isIpv6UniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
}

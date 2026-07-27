package com.vdzon.newsfeedbackend.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SsrfUrlValidatorTest {

    private fun resolverFor(vararg addresses: String): (String) -> List<InetAddress> =
        { addresses.map { InetAddress.getByName(it) } }

    @Test
    fun `rejects non-http scheme`() {
        val result = SsrfUrlValidator.validate("file:///etc/passwd", resolveHost = { emptyList() })
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects ftp scheme`() {
        val result = SsrfUrlValidator.validate("ftp://example.com/feed", resolveHost = { emptyList() })
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `accepts https scheme case-insensitively`() {
        val result = SsrfUrlValidator.validate("HTTPS://example.com/feed", resolverFor("93.184.216.34"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Valid)
    }

    @Test
    fun `rejects loopback ipv4`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("127.0.0.1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects loopback ipv6`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("::1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects link-local ipv4`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("169.254.1.1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects link-local ipv6`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("fe80::1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects private rfc1918 10-8`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("10.1.2.3"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects private rfc1918 172-16-12`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("172.16.5.5"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects private rfc1918 192-168-16`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("192.168.1.1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects ipv6 unique local fc00-7`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("fd12:3456:789a:1::1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects multicast ipv4`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("224.0.0.1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects multicast ipv6`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("ff02::1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects ipv4-mapped ipv6 loopback bypass`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("::ffff:127.0.0.1"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects ipv4-mapped ipv6 private bypass`() {
        val result = SsrfUrlValidator.validate("http://internal.example/feed", resolverFor("::ffff:10.0.0.5"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `accepts valid public url`() {
        val result = SsrfUrlValidator.validate("https://example.com/feed.xml", resolverFor("93.184.216.34"))
        assertTrue(result is SsrfUrlValidator.ValidationResult.Valid)
    }

    @Test
    fun `rejects when at least one of multiple resolved addresses is blocked`() {
        val result = SsrfUrlValidator.validate(
            "http://internal.example/feed",
            resolverFor("93.184.216.34", "10.0.0.5")
        )
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects non-resolvable host`() {
        val result = SsrfUrlValidator.validate(
            "http://this-host-does-not-resolve.invalid/feed",
            resolveHost = { throw java.net.UnknownHostException("not found") },
        )
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rejects malformed url`() {
        val result = SsrfUrlValidator.validate("not a url", resolveHost = { emptyList() })
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `uses real dns resolution by default for loopback host`() {
        val result = SsrfUrlValidator.validate("http://localhost/feed")
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `allows loopback when explicitly permitted (e2e test escape hatch)`() {
        val result = SsrfUrlValidator.validate(
            "http://internal.example/feed",
            resolverFor("127.0.0.1"),
            allowLoopback = true,
        )
        assertTrue(result is SsrfUrlValidator.ValidationResult.Valid)
    }

    @Test
    fun `still rejects private rfc1918 when loopback is allowed`() {
        val result = SsrfUrlValidator.validate(
            "http://internal.example/feed",
            resolverFor("10.1.2.3"),
            allowLoopback = true,
        )
        assertTrue(result is SsrfUrlValidator.ValidationResult.Invalid)
    }
}

package com.vdzon.newsfeedbackend.version.api

import org.springframework.boot.SpringBootVersion
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/version")
class VersionController {

    @GetMapping
    fun version(): ResponseEntity<Map<String, String>> {
        val sha = System.getenv("BUILD_SHA")?.takeIf { it.isNotBlank() } ?: "unknown"
        val buildTime = System.getenv("BUILD_TIME")?.takeIf { it.isNotBlank() } ?: "unknown"
        val body = mapOf(
            "appName" to "Personal News Feed",
            "sha" to sha,
            "buildTime" to buildTime,
            // Backwards-compat met clients die nog `gitSha` lezen.
            "gitSha" to sha,
            "springVersion" to SpringBootVersion.getVersion(),
            "environment" to (System.getenv("APP_ENVIRONMENT")?.takeIf { it.isNotBlank() } ?: "prod")
        )
        return ResponseEntity.ok()
            .header("Cache-Control", "no-cache, must-revalidate")
            .body(body)
    }
}

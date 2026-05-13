package com.vdzon.newsfeedbackend.version.api

import org.springframework.boot.SpringBootVersion
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/version")
class VersionController {

    @GetMapping
    fun version(): Map<String, String> = mapOf(
        "gitSha" to (System.getenv("BUILD_SHA")?.takeIf { it.isNotBlank() } ?: "unknown"),
        "springVersion" to SpringBootVersion.getVersion()
    )
}

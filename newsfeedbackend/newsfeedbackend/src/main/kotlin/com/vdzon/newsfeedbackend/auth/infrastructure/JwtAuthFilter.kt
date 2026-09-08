package com.vdzon.newsfeedbackend.auth.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwt: JwtService,
    private val googleLoginConfig: GoogleLoginConfig,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        if (googleLoginConfig.previewSkipGoogle) {
            val username = googleLoginConfig.allowedUsernames.firstOrNull() ?: PREVIEW_USERNAME
            authenticate(username, "admin")
            chain.doFilter(request, response)
            return
        }
        val token = resolveToken(request)
        if (token != null) {
            val parsed = jwt.validate(token)
            if (parsed != null) {
                val (username, role) = parsed
                // Een allowlist-wijziging trekt ook reeds uitgegeven sessies direct in.
                if (googleLoginConfig.passwordAuthEnabled || username in googleLoginConfig.allowedUsernames) {
                    authenticate(username, role)
                }
            }
        }
        chain.doFilter(request, response)
    }

    private fun authenticate(username: String, role: String) {
        // Spring Security verwacht ROLE_-prefix wanneer je hasRole(...) gebruikt.
        val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.uppercase()}"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, null, authorities)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) return header.removePrefix("Bearer ").trim()
        // Audio endpoint accepts the token as a query parameter
        val uri = request.requestURI
        if (uri.startsWith("/api/podcasts/") && uri.endsWith("/audio")) {
            request.getParameter("token")?.let { return it }
        }
        return null
    }

    companion object {
        const val PREVIEW_USERNAME = "preview"
    }
}

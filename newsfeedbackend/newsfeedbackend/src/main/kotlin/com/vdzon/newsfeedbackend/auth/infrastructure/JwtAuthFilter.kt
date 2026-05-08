package com.vdzon.newsfeedbackend.auth.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(private val jwt: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val token = resolveToken(request)
        if (token != null) {
            val username = jwt.validate(token)
            if (username != null) {
                val auth = UsernamePasswordAuthenticationToken(username, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        chain.doFilter(request, response)
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
}

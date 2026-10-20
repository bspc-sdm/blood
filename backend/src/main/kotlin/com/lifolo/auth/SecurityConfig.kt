package com.lifolo.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Spring Security only verifies the Firebase ID token (Authorization: Bearer …) and records the result.
 * It never rejects a request by itself: whether an action needs login is decided per action (guest actions,
 * web-panel cookie, caregiver), and the exact Node error message depends on the body's `lang` — so
 * [AuthService] turns a recorded failure into the right 401/403 later.
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, identity: IdentityProvider): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            // not a bean, so Spring Boot does not also register it as a plain servlet filter
            .addFilterBefore(FirebaseTokenFilter(identity), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

/** Result of token verification when it failed (read by [AuthService.decodeUser]). */
data class AuthFailure(val kind: IdentityException.Kind)

class FirebaseAuthentication(val token: DecodedToken) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""
    override fun getPrincipal(): Any = token
}

class FirebaseTokenFilter(private val identity: IdentityProvider) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization") ?: ""
        if (header.startsWith("Bearer ")) {
            val token = header.substring(7)
            if (token.isNotEmpty()) {
                try {
                    val decoded = identity.verifyIdToken(token, true)
                    SecurityContextHolder.getContext().authentication = FirebaseAuthentication(decoded)
                    request.setAttribute(ATTR_TOKEN, decoded)
                } catch (e: IdentityException) {
                    request.setAttribute(ATTR_FAILURE, AuthFailure(e.kind))
                } catch (e: Exception) {
                    request.setAttribute(ATTR_FAILURE, AuthFailure(IdentityException.Kind.OTHER))
                }
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val ATTR_TOKEN = "lifolo.auth.token"
        const val ATTR_FAILURE = "lifolo.auth.failure"
    }
}

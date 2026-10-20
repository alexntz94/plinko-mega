package com.plinko.config;

import com.plinko.bo.security.AdminAuthenticationProvider;
import com.plinko.bo.security.AdminUserService;
import com.plinko.common.ClientIp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * The public demo lobby and its play link (pages exist only with DEMO_MODE=true): no sign-in, no session, no
     * scripts; images, styles and fonts from this site only.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain demoChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/demo", "/api/demo/play")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/demo", "/api/demo/play").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/demo/play").permitAll()
                        .anyRequest().denyAll())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; img-src 'self'; style-src 'self'; "
                        + "font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")));
        return http.build();
    }

    /** Game API and mock PAM: stateless, authenticated by game session / HMAC signature in the controllers. */
    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**", "/mock-pam/**", "/actuator/health", AdminPortConfig.METRICS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/game/session", "/api/game/resume", "/api/game/play", "/api/game/history",
                                "/api/game/event").permitAll()
                        .requestMatchers(HttpMethod.POST, "/mock-pam/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // read by the monitoring scraper; served on the private admin port only (AdminPortConfig)
                        .requestMatchers(HttpMethod.GET, AdminPortConfig.METRICS).permitAll()
                        .anyRequest().denyAll())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    /** Admin panel: form login with 2FA, CSRF protection, role checks on every action. */
    @Bean
    @Order(2)
    public SecurityFilterChain adminChain(HttpSecurity http, AdminAuthenticationProvider provider, AdminUserService users,
                                          ClientIp clientIp) throws Exception {
        // Its own manager, without a parent: otherwise Spring also registers the provider bean globally and
        // asks it a second time after a failure, so every wrong attempt would be checked (and counted) twice.
        http.authenticationManager(new org.springframework.security.authentication.ProviderManager(java.util.List.of(provider)))
                .addFilterBefore(new AccountStateFilter(users), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/admin/login/code", "/admin/denied", "/admin/forgot", "/admin-assets/**", "/error").permitAll()
                        .requestMatchers("/admin/2fa/**").hasAuthority(AdminAuthenticationProvider.ROLE_ENROLL)
                        .requestMatchers("/admin/password/**").hasAuthority(AdminAuthenticationProvider.ROLE_PASSWORD_CHANGE)
                        .requestMatchers("/admin/**").hasRole("VIEWER")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .authenticationDetailsSource(request -> new AdminAuthenticationProvider.Details(request, clientIp))
                        .successHandler((request, response, authentication) -> {
                            java.util.Set<String> granted = org.springframework.security.core.authority.AuthorityUtils
                                    .authorityListToSet(authentication.getAuthorities());
                            AccountStateFilter.remember(request.getSession(), users.find(authentication.getName()).orElseThrow());
                            String next = granted.contains(AdminAuthenticationProvider.ROLE_ENROLL) ? "/admin/2fa/setup"
                                    : granted.contains(AdminAuthenticationProvider.ROLE_PASSWORD_CHANGE) ? "/admin/password/change"
                                    : "/admin";
                            response.sendRedirect(request.getContextPath() + next);
                        })
                        .failureHandler((request, response, exception) -> {
                            // Step 1 passed: continue on the code screen. A wrong code stays there while the
                            // step-1 result is still valid; otherwise the sign-in starts again at step 1.
                            if (exception instanceof AdminAuthenticationProvider.CodeRequiredException) {
                                response.sendRedirect(request.getContextPath() + "/admin/login/code");
                                return;
                            }
                            request.getSession().setAttribute("loginError", exception.getMessage());
                            boolean onCodeStep = request.getSession().getAttribute(AdminAuthenticationProvider.PENDING) != null
                                    && (request.getParameter("password") == null || request.getParameter("password").isEmpty());
                            response.sendRedirect(request.getContextPath() + (onCodeStep ? "/admin/login/code?error" : "/admin/login?error"));
                        }))
                .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout"))
                .sessionManagement(s -> s.sessionFixation(f -> f.newSession()))
                .exceptionHandling(e -> e.accessDeniedPage("/admin/denied"))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin")));
        return http.build();
    }
}

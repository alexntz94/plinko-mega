package com.plinko.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * With ADMIN_PORT set, the admin panel answers only on that port and the game API only on the main port.
 * The public proxy forwards to the main port, so no proxy mistake can expose the admin panel, and the admin
 * port is published on 127.0.0.1 only.
 */
@Configuration
public class AdminPortConfig {

    /** The metrics for monitoring: private like the admin panel, never on the public port. */
    public static final String METRICS = "/actuator/prometheus";

    private final int adminPort;
    private final int serverPort;

    public AdminPortConfig(@Value("${plinko.admin-port:0}") int adminPort, @Value("${server.port:8080}") int serverPort) {
        this.adminPort = adminPort;
        this.serverPort = serverPort;
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector() {
        return factory -> {
            if (adminPort > 0 && adminPort != serverPort) {
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(adminPort);
                factory.addAdditionalConnectors(connector);
            }
        };
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> portSeparation() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (adminPort > 0 && adminPort != serverPort) {
                    // Decoded and normalised by the container, as used for routing (so /%61dmin counts as /admin).
                    String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
                    boolean adminPath = path.equals("/admin") || path.startsWith("/admin/") || path.startsWith("/admin-assets/")
                            || path.equals(METRICS);
                    boolean onAdminPort = request.getLocalPort() == adminPort;
                    // The test-only mock PAM (absent unless enabled) is also reachable on the private admin port.
                    boolean allowed = onAdminPort ? adminPath || path.equals("/error") || path.startsWith("/mock-pam/") : !adminPath;
                    if (!allowed) {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

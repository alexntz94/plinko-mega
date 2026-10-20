package com.plinko.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Review 5.4: who may call every admin endpoint, generated from the controllers. Roles nest (ADMIN > OPERATOR > VIEWER);
 * everything under /admin needs at least VIEWER (SecurityConfig) unless listed as a sign-in step. A new endpoint makes
 * this test fail until it is added here with its role, and an action (POST) needs OPERATOR or ADMIN unless it is one
 * of the few a signed-in person does for their own account.
 */
class RoleMatrixTest {

    /** method + path -> who may call it. */
    static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("GET /admin", "VIEWER"),
            Map.entry("GET /admin/account", "VIEWER"),
            Map.entry("POST /admin/account/password", "VIEWER (own account)"),
            Map.entry("GET /admin/audit", "ADMIN"),
            Map.entry("GET /admin/audit.csv", "ADMIN"),
            Map.entry("GET /admin/bonus", "VIEWER"),
            Map.entry("POST /admin/bonus/propose", "OPERATOR"),
            Map.entry("GET /admin/changes", "VIEWER"),
            Map.entry("GET /admin/changes.csv", "VIEWER"),
            Map.entry("POST /admin/changes/{id}/approve", "ADMIN"),
            Map.entry("POST /admin/changes/{id}/reject", "OPERATOR"),
            Map.entry("GET /admin/finance", "VIEWER"),
            Map.entry("GET /admin/finance.csv", "VIEWER"),
            Map.entry("GET /admin/finance/wallet-check", "OPERATOR"),
            Map.entry("GET /admin/forgot", "PUBLIC (sign-in step)"),
            Map.entry("POST /admin/forgot", "PUBLIC (sign-in step)"),
            Map.entry("GET /admin/login", "PUBLIC (sign-in step)"),
            Map.entry("GET /admin/login/code", "PUBLIC (sign-in step)"),
            Map.entry("GET /admin/2fa/setup", "ENROLL (sign-in step)"),
            Map.entry("POST /admin/2fa/setup", "ENROLL (sign-in step)"),
            Map.entry("GET /admin/password/change", "PASSWORD_CHANGE (sign-in step)"),
            Map.entry("POST /admin/password/change", "PASSWORD_CHANGE (sign-in step)"),
            Map.entry("GET /admin/mock-pam", "ADMIN"),
            Map.entry("POST /admin/mock-pam", "ADMIN"),
            Map.entry("GET /admin/mock-pam/{playerId}/open", "ADMIN"),
            Map.entry("GET /admin/mock-pam/{playerId}/qr", "ADMIN"),
            Map.entry("GET /admin/odds", "VIEWER"),
            Map.entry("POST /admin/odds/propose", "OPERATOR"),
            Map.entry("POST /admin/odds/target-rtp", "OPERATOR"),
            Map.entry("GET /admin/players", "VIEWER"),
            Map.entry("GET /admin/players.csv", "VIEWER"),
            Map.entry("GET /admin/players/{id}", "VIEWER"),
            Map.entry("POST /admin/players/{id}/block", "OPERATOR"),
            Map.entry("POST /admin/players/{id}/refresh-balance", "OPERATOR"),
            Map.entry("GET /admin/rounds", "VIEWER"),
            Map.entry("GET /admin/rounds.csv", "VIEWER"),
            Map.entry("GET /admin/rounds/{id}", "VIEWER"),
            Map.entry("POST /admin/rounds/{id}/retry", "ADMIN"),
            Map.entry("POST /admin/rounds/{id}/settle", "ADMIN"),
            Map.entry("POST /admin/rounds/{id}/settle/send", "ADMIN"),
            Map.entry("GET /admin/settings", "VIEWER"),
            Map.entry("POST /admin/settings/currency", "OPERATOR"),
            Map.entry("POST /admin/settings/features", "OPERATOR"),
            Map.entry("POST /admin/settings/game-enabled", "OPERATOR"),
            Map.entry("POST /admin/settings/limits", "OPERATOR"),
            Map.entry("POST /admin/settings/restore", "OPERATOR"),
            Map.entry("POST /admin/settings/settlement-limits", "ADMIN"),
            Map.entry("POST /admin/settings/test-pam", "ADMIN"),
            Map.entry("GET /admin/themes", "VIEWER"),
            Map.entry("POST /admin/themes", "OPERATOR"),
            Map.entry("GET /admin/transactions", "VIEWER"),
            Map.entry("GET /admin/transactions.csv", "VIEWER"),
            Map.entry("GET /admin/users", "ADMIN"),
            Map.entry("POST /admin/users", "ADMIN"),
            Map.entry("POST /admin/users/reset-requests/{id}/dismiss", "ADMIN"),
            Map.entry("POST /admin/users/{username}/{action}", "ADMIN")));

    private static String role(String expression) {
        return switch (expression) {
            case "hasRole('ADMIN')" -> "ADMIN";
            case "hasRole('OPERATOR')", "hasAnyRole('ADMIN', 'OPERATOR')" -> "OPERATOR";
            case "hasRole('VIEWER')" -> "VIEWER";
            default -> "UNKNOWN " + expression;
        };
    }

    private static String urlRule(String verb, String path) {
        if (path.startsWith("/admin/login") || path.equals("/admin/forgot")) {
            return "PUBLIC (sign-in step)";
        }
        if (path.startsWith("/admin/2fa/")) {
            return "ENROLL (sign-in step)";
        }
        if (path.startsWith("/admin/password/")) {
            return "PASSWORD_CHANGE (sign-in step)";
        }
        return "POST".equals(verb) && path.equals("/admin/account/password") ? "VIEWER (own account)" : "VIEWER";
    }

    @Test
    void everyAdminEndpointHasTheExpectedRole() throws Exception {
        Map<String, String> found = new TreeMap<>();
        // every controller, the optional ones too (the mock wallet's pages exist only with the mock PAM)
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false,
                new org.springframework.mock.env.MockEnvironment().withProperty("plinko.mock-pam.enabled", "true")
                        .withProperty("plinko.demo.enabled", "true"));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        for (var candidate : scanner.findCandidateComponents("com.plinko.controller")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            PreAuthorize classRule = type.getAnnotation(PreAuthorize.class);
            RequestMapping base = type.getAnnotation(RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : type.getDeclaredMethods()) {
                String verb = null;
                String[] paths = null;
                if (method.isAnnotationPresent(GetMapping.class)) {
                    verb = "GET";
                    paths = method.getAnnotation(GetMapping.class).value();
                } else if (method.isAnnotationPresent(PostMapping.class)) {
                    verb = "POST";
                    paths = method.getAnnotation(PostMapping.class).value();
                } else if (method.isAnnotationPresent(PutMapping.class)) {
                    verb = "PUT";
                    paths = method.getAnnotation(PutMapping.class).value();
                } else if (method.isAnnotationPresent(DeleteMapping.class)) {
                    verb = "DELETE";
                    paths = method.getAnnotation(DeleteMapping.class).value();
                }
                if (verb == null) {
                    continue;
                }
                for (String path : paths) {
                    String full = prefix + path;
                    if (!full.startsWith("/admin")) {
                        continue;
                    }
                    PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
                    rule = rule != null ? rule : classRule;
                    found.put(verb + " " + full, rule != null ? role(rule.value()) : urlRule(verb, full));
                }
            }
        }
        assertThat(found).as("admin endpoints and who may call them (add a new endpoint to EXPECTED with its role)").isEqualTo(EXPECTED);
        // an action needs OPERATOR or ADMIN, apart from the sign-in steps and one's own password
        found.forEach((endpoint, role) -> {
            if (!endpoint.startsWith("GET ")) {
                assertThat(role).as(endpoint).isIn("OPERATOR", "ADMIN", "PUBLIC (sign-in step)", "ENROLL (sign-in step)",
                        "PASSWORD_CHANGE (sign-in step)", "VIEWER (own account)");
            }
        });
    }
}

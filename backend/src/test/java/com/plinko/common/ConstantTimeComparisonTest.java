package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Review 5.4: signatures, API keys and codes a caller sends are compared in constant time (MessageDigest.isEqual), so the
 * time an answer takes never tells how much of a guess was right; a new comparison written with equals() fails here.
 */
class ConstantTimeComparisonTest {

    @Test
    void safeEqualsMatchesOnlyTheSameValue() {
        String signature = Crypto.hmacSha256Hex("k".repeat(40), "data");
        assertThat(Crypto.safeEquals(signature, new String(signature.toCharArray()))).isTrue();
        assertThat(Crypto.safeEquals(signature, signature.substring(0, 63) + (signature.endsWith("0") ? "1" : "0"))).isFalse();
        assertThat(Crypto.safeEquals(signature, signature.substring(0, 32))).as("a prefix").isFalse();
        assertThat(Crypto.safeEquals(signature, signature + "0")).as("longer").isFalse();
        assertThat(Crypto.safeEquals(signature, signature.toUpperCase())).isFalse();
        assertThat(Crypto.safeEquals(null, signature)).isFalse();
        assertThat(Crypto.safeEquals(signature, null)).isFalse();
        assertThat(Crypto.safeEquals(null, null)).as("nothing never matches nothing").isFalse();
        assertThat(Crypto.safeEquals("", "")).isTrue();
        assertThat(Crypto.safeEquals("é", "e")).isFalse();
    }

    @Test
    void safeEqualsIsBuiltOnTheConstantTimeComparison() throws IOException {
        String crypto = Files.readString(Path.of("src/main/java/com/plinko/common/Crypto.java"));
        String body = crypto.substring(crypto.indexOf("public static boolean safeEquals"));
        body = body.substring(0, body.indexOf("\n    }"));
        assertThat(body).contains("MessageDigest.isEqual(").doesNotContain(".equals(");
    }

    /** Values a caller can send that are checked against a secret: signatures, HMACs, API keys, 2FA codes. */
    private static final Pattern SECRET_WITH_EQUALS = Pattern.compile(
            "(hmac\\w*\\([^;]*\\)\\s*\\.equals\\()"
                    + "|(\\.equals\\(\\s*Crypto\\.hmac)"
                    + "|((?i)(apiKey|signature|code)\\(?\\)?\\s*\\.equals\\(\\s*(config|properties|expected|Crypto|code\\())");

    @Test
    void noSignatureKeyOrCodeIsComparedWithEquals() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int n = 0; n < lines.size(); n++) {
                    if (SECRET_WITH_EQUALS.matcher(lines.get(n)).find()) {
                        found.add(file + ":" + (n + 1) + ": " + lines.get(n).strip());
                    }
                }
            }
        }
        assertThat(found).as("compare these with Crypto.safeEquals").isEmpty();
    }

    @Test
    void everyPlaceThatChecksAnHmacUsesSafeEquals() throws IOException {
        // the wallet's answers, the mock wallet's requests, stored signatures and 2FA codes
        for (String file : List.of("pam/HttpPamClient.java", "controller/mockpam/MockPamController.java", "common/KeyRing.java",
                "bo/security/Totp.java", "bo/audit/AuditService.java")) {
            assertThat(Files.readString(Path.of("src/main/java/com/plinko/" + file))).as(file).contains("Crypto.safeEquals(");
        }
    }
}

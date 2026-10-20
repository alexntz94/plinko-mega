package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.plinko.TestFixtures;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private final SecretBox box = new SecretBox(TestFixtures.properties());

    @Test
    void roundTripsAndHidesTheValue() {
        String stored = box.encrypt("totp:7", "JBSWY3DPEHPK3PXP");
        assertThat(stored).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(box.decrypt("totp:7", stored)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(box.encrypt("totp:7", "JBSWY3DPEHPK3PXP")).isNotEqualTo(stored); // random IV
    }

    @Test
    void refusesCopiedTamperedOrPlainValues() {
        String stored = box.encrypt("totp:7", "JBSWY3DPEHPK3PXP");
        assertThat(box.decrypt("totp:8", stored)).isNull();                    // copied to another account
        assertThat(box.decrypt("totp:7", stored.substring(0, stored.length() - 4) + "AAAA")).isNull();
        assertThat(box.decrypt("totp:7", "JBSWY3DPEHPK3PXP")).isNull();        // written in plain text
    }
}

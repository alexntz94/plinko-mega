package com.plinko.pam;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;

import com.plinko.TestFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpPamClientTest {

    @Test
    void aStoppedHttpClientIsReplacedInsteadOfFailingEveryWalletCall() throws Exception {
        HttpPamClient pam = new HttpPamClient(TestFixtures.properties(), JsonMapper.builder().build());
        HttpClient broken = pam.client();
        broken.shutdownNow();                             // what a dead selector thread leaves behind
        assertThat(broken.awaitTermination(Duration.ofSeconds(5))).isTrue();

        assertThat(pam.usable()).isTrue();
        assertThat(pam.client()).isNotSameAs(broken);
        assertThat(pam.client().isTerminated()).isFalse();
        assertThat(new PamClientHealth(pam).health().getStatus().getCode()).isEqualTo("UP");
    }
}

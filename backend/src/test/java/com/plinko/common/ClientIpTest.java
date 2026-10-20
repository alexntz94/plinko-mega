package com.plinko.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

    private static MockHttpServletRequest request(String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    void dockerUsesTheConnectionAndIgnoresTheHeader() {
        assertThat(new ClientIp(0).of(request("1.2.3.4"))).isEqualTo("10.0.0.9");
    }

    @Test
    void behindTheBalancerReadsTheVisitorItAppended() {
        assertThat(new ClientIp(2).of(request("203.0.113.7, 34.120.1.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void entriesWrittenByTheClientCannotFakeTheAddress() {
        // the client sends its own header; the balancer appends the real visitor and itself after it
        assertThat(new ClientIp(2).of(request("6.6.6.6, 7.7.7.7", "203.0.113.7, 34.120.1.1"))).isEqualTo("203.0.113.7");
        assertThat(new ClientIp(2).of(request("6.6.6.6,7.7.7.7,203.0.113.7,34.120.1.1"))).isEqualTo("203.0.113.7");
        assertThat(new ClientIp(2).of(request("2001:db8::1, 34.120.1.1"))).isEqualTo("2001:db8::1");
    }

    @Test
    void missingOrOddHeaderFallsBackToTheConnection() {
        assertThat(new ClientIp(2).of(request())).isEqualTo("10.0.0.9");
        assertThat(new ClientIp(2).of(request("34.120.1.1"))).isEqualTo("10.0.0.9");
        assertThat(new ClientIp(2).of(request("<script>, 34.120.1.1"))).isEqualTo("10.0.0.9");
    }
}

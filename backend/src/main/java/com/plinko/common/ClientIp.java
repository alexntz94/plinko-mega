package com.plinko.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The visitor's address, for the per-address login throttles and the audit log.
 *
 * <p>With {@code CLIENT_IP_HOPS=0} (default, Docker) it is the connection's address. Behind a load balancer every
 * connection comes from the balancer, so set it to the number of X-Forwarded-For entries your own proxies append
 * (2 for a Google Cloud HTTPS load balancer: "client, balancer"). The address is then read that many entries from
 * the right; anything further left was written by the client and is ignored, so it cannot be faked.
 */
@Component
public class ClientIp {

    private final int hops;

    public ClientIp(@Value("${plinko.client-ip-hops:0}") int hops) {
        this.hops = hops;
    }

    public String of(HttpServletRequest request) {
        if (hops <= 0) {
            return request.getRemoteAddr();
        }
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String entry : header.split(",")) {
                if (!entry.isBlank()) {
                    entries.add(entry.strip());
                }
            }
        }
        int index = entries.size() - hops;
        // not through the balancer (the service only accepts it, so this should not happen): the connection's address
        if (index < 0 || !looksLikeIp(entries.get(index))) {
            return request.getRemoteAddr();
        }
        return entries.get(index);
    }

    private static boolean looksLikeIp(String value) {
        return value.length() <= 45 && value.matches("[0-9A-Fa-f:.]+");
    }
}

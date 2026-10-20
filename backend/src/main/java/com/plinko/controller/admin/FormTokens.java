package com.plinko.controller.admin;

import java.util.Iterator;
import java.util.LinkedHashSet;

import com.plinko.common.Crypto;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/**
 * N14.15: one-time form tokens, checked on the server. The page that shows a form issues a token, kept in the admin's
 * HTTP session (the newest {@value #KEPT} per session); the form sends it back in a hidden field, and the action is done
 * only if that token is still unused, which uses it up. A form sent twice (a double click, a resend after a slow
 * answer) is done once; one sent without a token from its page is refused.
 */
@Component
public class FormTokens {

    /** The hidden field's name. */
    public static final String FIELD = "formToken";
    /** What the admin sees when a token is missing or already used. */
    public static final String REFUSED = "This form was already sent; reload the page";
    /** Tokens kept per session: enough for a few open tabs, the oldest dropped first. */
    static final int KEPT = 20;

    private static final String ATTRIBUTE = FormTokens.class.getName();

    /** A new token for a page that shows one of the guarded forms. */
    public String issue(HttpSession session) {
        String token = Crypto.randomToken(18);
        synchronized (WebUtils.getSessionMutex(session)) {
            LinkedHashSet<String> tokens = tokens(session);
            tokens.add(token);
            for (Iterator<String> oldest = tokens.iterator(); tokens.size() > KEPT; ) {
                oldest.next();
                oldest.remove();
            }
            session.setAttribute(ATTRIBUTE, tokens);
        }
        return token;
    }

    /** True once for a token this session issued; false for a missing, unknown or already used one. */
    public boolean use(HttpSession session, String token) {
        if (session == null || token == null || token.isBlank()) {
            return false;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            LinkedHashSet<String> tokens = tokens(session);
            boolean issued = tokens.remove(token);
            session.setAttribute(ATTRIBUTE, tokens);
            return issued;
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashSet<String> tokens(HttpSession session) {
        Object kept = session.getAttribute(ATTRIBUTE);
        return kept instanceof LinkedHashSet<?> set ? (LinkedHashSet<String>) set : new LinkedHashSet<>();
    }
}

package com.fluxguard.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdminAuthFilter}.
 *
 * <p>Pure Java — no Spring context, no Redis.
 */
class AdminAuthFilterTest {

    private static final String CORRECT_KEY         = "secret-key";
    private static final String ACTOR_ATTR          = "X-Admin-Actor";
    private static final String HEADER_KEY          = "X-Admin-Api-Key";
    private static final int    STATUS_OK           = 200;
    private static final int    STATUS_UNAUTHORIZED = 401;

    private AdminAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter   = new AdminAuthFilter(CORRECT_KEY);
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void correctKeyAllowsRequestAndSetsActor() throws Exception {
        request.addHeader(HEADER_KEY, CORRECT_KEY);

        final boolean result = filter.preHandle(request, response, new Object());

        assertTrue(result);
        assertEquals("admin", request.getAttribute(ACTOR_ATTR));
        assertEquals(STATUS_OK, response.getStatus());
    }

    @Test
    void wrongKeyReturns401AndNoActorSet() throws Exception {
        request.addHeader(HEADER_KEY, "wrong-key");

        final boolean result = filter.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(STATUS_UNAUTHORIZED, response.getStatus());
        assertNull(request.getAttribute(ACTOR_ATTR));
    }

    @Test
    void missingHeaderReturns401() throws Exception {
        final boolean result = filter.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(STATUS_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void emptyStringHeaderReturns401() throws Exception {
        request.addHeader(HEADER_KEY, "");

        final boolean result = filter.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(STATUS_UNAUTHORIZED, response.getStatus());
    }
}

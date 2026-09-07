package uz.greenwhite.gateway.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The proxy supplies the query separator itself, so HttpRequestService can concatenate
 * base + uri + params verbatim -- the documented BMB contract. Without this, the separator
 * would have to be inferred at assembly time, and a path segment ("Document_Foo") cannot be
 * told apart from a valueless query flag ("debug").
 */
class ProxyControllerQueryStringTest {

    private static MockHttpServletRequest withQuery(String query) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString(query);
        return request;
    }

    @Test
    void queryStringGetsItsLeadingQuestionMark() {
        assertEquals("?a=1&b=2", ProxyController.querySeparated(withQuery("a=1&b=2")));
    }

    @Test
    void valuelessFlagAlsoGetsIt() {
        assertEquals("?debug", ProxyController.querySeparated(withQuery("debug")));
    }

    @Test
    void absentQueryStringStaysNull() {
        assertNull(ProxyController.querySeparated(withQuery(null)));
    }

    @Test
    void blankQueryStringStaysNull() {
        assertNull(ProxyController.querySeparated(withQuery("   ")));
    }
}

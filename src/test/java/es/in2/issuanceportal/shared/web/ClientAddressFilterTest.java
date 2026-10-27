package es.in2.issuanceportal.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ClientAddressFilterTest {

    private static final String NGINX = "172.18.0.9";

    @Test
    void doFilter_oneProxy_takesTheEntryNginxAppended() throws Exception {
        HttpServletRequest seen = filter(1, NGINX, "203.0.113.7, 198.51.100.20");

        assertThat(seen.getRemoteAddr()).isEqualTo("198.51.100.20");
    }

    @Test
    void doFilter_twoProxies_skipsTheCloudFrontEntryTheAlbAppended() throws Exception {
        HttpServletRequest seen = filter(2, "10.0.1.15", "203.0.113.7, 198.51.100.20, 130.176.0.1");

        assertThat(seen.getRemoteAddr()).isEqualTo("198.51.100.20");
    }

    @Test
    void doFilter_entriesInSeveralHeaders_readsThemInOrder() throws Exception {
        MockHttpServletRequest request = request(NGINX);
        request.addHeader(ClientAddressFilter.X_FORWARDED_FOR, "203.0.113.7");
        request.addHeader(ClientAddressFilter.X_FORWARDED_FOR, "198.51.100.20");

        assertThat(run(new ClientAddressFilter(1), request).getRemoteAddr()).isEqualTo("198.51.100.20");
    }

    @Test
    void doFilter_fewerEntriesThanProxies_takesTheLeftmost() throws Exception {
        HttpServletRequest seen = filter(2, NGINX, "198.51.100.20");

        assertThat(seen.getRemoteAddr()).isEqualTo("198.51.100.20");
    }

    @Test
    void doFilter_noHeaderAndOneProxy_takesThePeer() throws Exception {
        HttpServletRequest seen = run(new ClientAddressFilter(1), request(NGINX));

        assertThat(seen.getRemoteAddr()).isEqualTo(NGINX);
    }

    @Test
    void doFilter_noProxies_ignoresTheHeader() throws Exception {
        HttpServletRequest seen = filter(0, "198.51.100.20", "203.0.113.7");

        assertThat(seen.getRemoteAddr()).isEqualTo("198.51.100.20");
    }

    @Test
    void doFilter_anyRequest_hidesForwardedForAndForwardedButNotTheHost() throws Exception {
        MockHttpServletRequest request = request(NGINX);
        request.addHeader(ClientAddressFilter.X_FORWARDED_FOR, "203.0.113.7");
        request.addHeader("Forwarded", "for=203.0.113.7;host=kpmg.example.org");
        request.addHeader("X-Forwarded-Host", "cgcom.example.org");

        HttpServletRequest seen = run(new ClientAddressFilter(1), request);

        assertThat(seen.getHeader("x-forwarded-for")).isNull();
        assertThat(seen.getHeaders("Forwarded").hasMoreElements()).isFalse();
        assertThat(Collections.list(seen.getHeaderNames())).containsExactly("X-Forwarded-Host");
        assertThat(seen.getHeader("X-Forwarded-Host")).isEqualTo("cgcom.example.org");
    }

    @Test
    void new_negativeProxies_isRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientAddressFilter(-1));
    }

    private static HttpServletRequest filter(int proxies, String peer, String forwardedFor) throws Exception {
        MockHttpServletRequest request = request(peer);
        request.addHeader(ClientAddressFilter.X_FORWARDED_FOR, forwardedFor);
        return run(new ClientAddressFilter(proxies), request);
    }

    private static MockHttpServletRequest request(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/issuance-portal/api/v1/incidents");
        request.setRemoteAddr(peer);
        return request;
    }

    private static HttpServletRequest run(ClientAddressFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}

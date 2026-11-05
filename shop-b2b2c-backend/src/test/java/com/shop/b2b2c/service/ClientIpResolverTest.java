/*
 * 文件：ClientIpResolverTest.java
 * 用途：隔离验证限流用真实客户端 IP 解析：仅可信代理才读取 X-Forwarded-For，从右向左取首个非可信合法 IP，
 *       直连伪造头不生效，CIDR 可信代理与 IPv6 loopback 生效，非法值回退 remoteAddr。
 * 维护注意：仅使用 MockHttpServletRequest，不启动容器、不访问数据库/Redis/网络；不替代真实 Nginx 链路联调。
 */
package com.shop.b2b2c.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private static final String TRUSTED = "127.0.0.1,::1,10.0.0.0/8";
    private final ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

    private String resolve(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return resolver.resolve(request);
    }

    @Test
    void directClientCannotSpoofForwardedFor() {
        assertEquals("203.0.113.10", resolve("203.0.113.10", "198.51.100.7"));
    }

    @Test
    void trustedProxyUsesForwardedClientAddress() {
        assertEquals("198.51.100.7", resolve("127.0.0.1", "198.51.100.7"));
    }

    @Test
    void multipleProxiesWalkRightToLeftAndSkipTrustedHops() {
        assertEquals("203.0.113.9", resolve("127.0.0.1", "203.0.113.9, 10.1.2.3, 127.0.0.1"));
    }

    @Test
    void trustedCidrProxyIsRecognised() {
        assertEquals("198.51.100.20", resolve("10.1.2.3", "198.51.100.20"));
    }

    @Test
    void invalidForwardedEntriesAreIgnored() {
        assertEquals("203.0.113.5", resolve("127.0.0.1", "203.0.113.5, not-an-ip"));
    }

    @Test
    void allTrustedOrIllegalEntriesFallBackToRemoteAddr() {
        assertEquals("127.0.0.1", resolve("127.0.0.1", "127.0.0.1, 10.9.9.9"));
        assertEquals("127.0.0.1", resolve("127.0.0.1", "<script>, 10.0.0.1"));
    }

    @Test
    void ipv6LoopbackProxyUsesForwardedClientAddress() {
        assertEquals("2001:db8::1", resolve("::1", "2001:db8::1"));
    }

    @Test
    void missingForwardedHeaderUsesRemoteAddr() {
        assertEquals("127.0.0.1", resolve("127.0.0.1", null));
        assertEquals("127.0.0.1", resolve("127.0.0.1", "  "));
    }
}

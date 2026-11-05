/*
 * 文件：ClientIpResolver.java
 * 用途：为登录/建单/查单/代付限流解析真实客户端 IP。
 * 维护注意：仅当直连 remoteAddr 命中 app.security.trusted-proxies（默认 127.0.0.1,::1，支持 IPv4 CIDR）时才读取
 * X-Forwarded-For；从右向左取首个非可信合法 IP，非法项跳过，全部不可用时回退 remoteAddr。直连客户端伪造
 * X-Forwarded-For/X-Real-IP 一律忽略。解析结果只用于限流桶，不做信任授权；Nginx 侧必须覆盖而不是追加
 * X-Forwarded-For（$remote_addr），否则该列表可被外部伪造。
 */
package com.shop.b2b2c.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ClientIpResolver {

    private static final String DEFAULT_TRUSTED_PROXIES = "127.0.0.1,::1";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<String> trustedExact;
    private final List<int[]> trustedIpv4Cidrs;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:" + DEFAULT_TRUSTED_PROXIES + "}")
                            String trustedProxies) {
        List<String> exact = new ArrayList<>();
        List<int[]> cidrs = new ArrayList<>();
        for (String raw : (trustedProxies == null ? DEFAULT_TRUSTED_PROXIES : trustedProxies).split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            int slash = item.indexOf('/');
            if (slash > 0 && isValidIpv4(item.substring(0, slash))) {
                int prefix = parsePrefix(item.substring(slash + 1));
                if (prefix >= 0) {
                    cidrs.add(new int[]{ipv4ToInt(item.substring(0, slash)), prefix});
                    continue;
                }
            }
            exact.add(item);
        }
        this.trustedExact = List.copyOf(exact);
        this.trustedIpv4Cidrs = List.copyOf(cidrs);
    }

    /**
     * 返回用于限流的客户端 IP；无法解析时返回空串（由限流器按无效地址处理）。
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "";
        }
        if (!isTrusted(remote.trim())) {
            return remote.trim();
        }
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remote.trim();
        }
        String[] parts = forwarded.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (candidate.isEmpty() || !isValidIp(candidate)) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return remote.trim();
    }

    private boolean isTrusted(String ip) {
        for (String exact : trustedExact) {
            if (exact.equalsIgnoreCase(ip)) {
                return true;
            }
        }
        if (!isValidIpv4(ip)) {
            return false;
        }
        int value = ipv4ToInt(ip);
        for (int[] cidr : trustedIpv4Cidrs) {
            int mask = cidr[1] == 0 ? 0 : (-1 << (32 - cidr[1]));
            if ((value & mask) == (cidr[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    private static int parsePrefix(String raw) {
        try {
            int prefix = Integer.parseInt(raw.trim());
            return prefix >= 0 && prefix <= 32 ? prefix : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isValidIp(String candidate) {
        return isValidIpv4(candidate) || isValidIpv6(candidate);
    }

    private static boolean isValidIpv4(String candidate) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 仅接受十六进制/冒号/点组成的 IPv6 文本，避免 InetAddress 解析主机名或触发 DNS。
     */
    private static boolean isValidIpv6(String candidate) {
        if (!candidate.contains(":") || candidate.length() > 45) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') {
                return false;
            }
        }
        try {
            return java.net.InetAddress.getByName(candidate).getAddress().length == 16;
        } catch (Exception e) {
            return false;
        }
    }

    private static int ipv4ToInt(String ip) {
        String[] parts = ip.split("\\.");
        int value = 0;
        for (String part : parts) {
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }
}

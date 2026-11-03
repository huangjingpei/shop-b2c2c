/*
 * 文件：CorsOrigins.java
 * 用途：解析浏览器跨域来源的精确白名单，禁止通配、路径及不合法来源。
 * 维护注意：空配置关闭跨域但不阻止同源请求；auto仅从受控前台配置派生来源，不信任请求Host/转发头；HTTPS代理终止场景须允许公网来源。仅支持HTTP(S) origin，无Cookie授权；配置错误须阻断启动。
 */
package com.shop.b2b2c.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.LinkedHashSet;

@Component
public class CorsOrigins {
    private final String[] origins;

    @org.springframework.beans.factory.annotation.Autowired
    public CorsOrigins(@Value("${app.security.cors-allowed-origins:auto}") String configured,
            @Value("${app.frontend.storefront-url:http://localhost:3002}") String frontend) {
        this("auto".equals(configured) ? defaultOrigins(frontend) : configured);
    }

    private static String defaultOrigins(String frontend) {
        try {
            URI uri = URI.create(frontend);
            if (uri.getRawUserInfo() != null) throw new IllegalArgumentException();
            String origin = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
            return "http://localhost:3001,http://localhost:3002," + origin;
        } catch (Exception e) {
            throw new IllegalArgumentException("前台地址无法生成安全跨域来源，请设置精确白名单");
        }
    }

    public CorsOrigins(String configured) {
        var values = new LinkedHashSet<String>();
        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(",", -1)) {
                String value = item.strip();
                try {
                    URI uri = URI.create(value);
                    if (value.contains("*") || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                            || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                            || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                            || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    // 不回显配置原文，避免错误URL中的凭据进入启动日志。
                    throw new IllegalArgumentException("跨域白名单必须是逗号分隔的HTTP(S)精确来源，不允许通配、路径或凭据");
                }
                values.add(value);
            }
        }
        origins = values.toArray(String[]::new);
    }

    public String[] allowedOrigins() { return origins.clone(); }
}

/*
 * 文件：CaptchaServiceTest.java
 * 用途：验证图形验证码签发、一次性失效、错误码拒绝与强制开关行为。
 * 维护注意：仅内存运行，不启动 Spring；不校验 PNG 像素内容，只确认返回可用的 data URI。
 * 真实多实例共享存储、限流与前端识别体验需用户手动联调。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {

    @Test
    void issuedCaptchaCanBeVerifiedExactlyOnce() {
        CaptchaService service = new CaptchaService(false, 300);
        Map<String, String> captcha = service.issue();
        String code = service.peekCode(captcha.get("captchaId"));

        assertEquals(5, code.length());
        assertTrue(captcha.get("image").startsWith("data:image/png;base64,"));
        service.verify(captcha.get("captchaId"), code);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.verify(captcha.get("captchaId"), code)).getCode());
    }

    @Test
    void wrongCodeIsRejectedAndCannotBeRetried() {
        CaptchaService service = new CaptchaService(false, 300);
        Map<String, String> captcha = service.issue();

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.verify(captcha.get("captchaId"), "00000")).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.verify(captcha.get("captchaId"),
                        service.peekCode(captcha.get("captchaId")))).getCode());
    }

    @Test
    void requiredCaptchaRejectsMissingFieldsButOptionalModeIsNoOp() {
        assertFalse(new CaptchaService(false, 300).required());
        new CaptchaService(false, 300).verify(null, null);

        CaptchaService required = new CaptchaService(true, 300);
        assertTrue(required.required());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> required.verify(null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> required.verify("id-only", null)).getCode());
    }
}

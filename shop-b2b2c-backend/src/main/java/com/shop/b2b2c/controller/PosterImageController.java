/*
 * 文件：PosterImageController.java
 * 用途：公开读取已落盘代付海报 PNG，供分享页与商家预览直接作为图片地址使用。
 * 维护注意：路径仅接受 SHA-256 文件名，租户与凭证校验已在生成/读取业务接口完成；文件自身即公开分享物料，不包含卡密或订单隐私。
 * 长缓存按不可变内容设置，文件被替换时同名 URL 内容会变化，缓存有效期须与重新生成策略一致；缺文件返回 404，不代理外部地址。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.service.PosterStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class PosterImageController {

    private final PosterStorageService storage;

    @GetMapping("/api/v1/peer-pay/poster/image/{fileName:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String fileName) {
        byte[] png = storage.read(fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }
}

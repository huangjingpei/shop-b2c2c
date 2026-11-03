/*
 * 文件：PublicStoreController.java
 * 用途：提供不依赖商品存在的公开营业店铺资料入口。
 * 维护注意：仅输出既有六字段白名单；未营业和不存在统一404业务码，禁止暴露身份、费率和收款配置。无写入或事务，不改变历史订单查询资格。
 */
package com.shop.b2b2c.controller;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.common.PublicStoreView;
import com.shop.b2b2c.common.R;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicStoreController {
    private final MerchantStoreMapper stores;

    @GetMapping("/api/v1/public/stores/{id}")
    public R<PublicStoreView> getStore(@PathVariable Long id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (id == null || id <= 0) throw new BusinessException(400, "店铺编号不正确");
        var store = stores.findById(id).orElse(null);
        if (store == null || !Integer.valueOf(1).equals(store.getStatus())) {
            throw new BusinessException(404, "店铺不存在或暂未营业");
        }
        return R.ok(PublicStoreView.from(store));
    }
}

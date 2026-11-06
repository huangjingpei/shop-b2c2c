/*
 * 文件：MerchantStoreGuard.java
 * 用途：O4 商家侧写接口统一店铺营业状态闸门：商品发布/编辑、库存调整、卡密作废、发货/推单/虚拟履约、店铺资料修改
 *      在执行业务前必须调用 requireOpen，店铺非营业（0-待开通/2-打烊/3-封禁/4-注销）一律 409。
 * 维护注意：
 * 1. 只拦截商家侧写操作；读接口（列表/详情/海报）与退款处理不经过本类——冻结店铺的退款、售后必须继续履约。
 * 2. 公开侧交易拦截仍走 TradeGuards.requirePurchasable，本类不重复实现；两端判定口径都是 merchant_store.status==1。
 * 3. 每次请求读库判定，不信任会话缓存的店铺状态；店铺不存在按 404 处理，不回退默认店铺。
 * 4. O9：requireQualificationValid 只拦“上新”（商品创建）——身份证/营业执照任一已到期（含到期当天）即 409；
 *    有效期未采集（空）不参与判定；不影响已成交订单履约与既有商品编辑，商家更新有效期后即时恢复。
 */
package com.shop.b2b2c.service;

import com.shop.b2b2c.common.BusinessException;
import com.shop.b2b2c.entity.MerchantStore;
import com.shop.b2b2c.mapper.MerchantStoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantStoreGuard {

    private final MerchantStoreMapper storeMapper;

    /** 按店铺ID加载后判定；店铺不存在按 404 处理，不回退默认店铺。 */
    public void requireOpen(Long merchantId) {
        requireOpen(merchantId == null ? null : storeMapper.selectById(merchantId));
    }

    /** 店铺必须处于正常营业(1)；待开通给出明确引导文案，其余状态统一拒绝。已加载店铺的入口直接传实体避免重复查询。 */
    public void requireOpen(MerchantStore store) {
        if (store == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        if (Integer.valueOf(1).equals(store.getStatus())) {
            return;
        }
        if (Integer.valueOf(0).equals(store.getStatus())) {
            throw new BusinessException(409, "店铺待开通：请先完成开店协议签署，待店铺开通后再操作");
        }
        throw new BusinessException(409, "店铺当前非营业状态，无法执行该操作");
    }

    /** O9 资质闸门（按店铺ID加载后判定）；店铺不存在按 404 处理。 */
    public void requireQualificationValid(Long merchantId) {
        requireQualificationValid(merchantId == null ? null : storeMapper.selectById(merchantId));
    }

    /**
     * O9 资质异常限制上新：任一已采集有效期到期（含当天到期）即拒绝；
     * 有效期为空表示未采集/长期，不拦截。判定实时计算，不落“资质异常”标记列。
     */
    public void requireQualificationValid(MerchantStore store) {
        if (store == null) {
            throw new BusinessException(404, "店铺不存在");
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        boolean idCardExpired = store.getIdCardExpiry() != null && !store.getIdCardExpiry().isAfter(today);
        boolean licenseExpired = store.getLicenseExpiry() != null && !store.getLicenseExpiry().isAfter(today);
        if (idCardExpired || licenseExpired) {
            String which = idCardExpired && licenseExpired ? "身份证与营业执照"
                    : idCardExpired ? "身份证" : "营业执照";
            throw new BusinessException(409, which + "有效期已到期，请先在商家资料页更新资质有效期，再发布新商品");
        }
    }
}

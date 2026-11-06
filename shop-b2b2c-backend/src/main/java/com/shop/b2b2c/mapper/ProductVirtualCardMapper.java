/*
 * 文件：ProductVirtualCardMapper.java
 * 用途：卡密查询、事务内锁定候选卡、未售状态条件认领及全额退款后的已售卡密作废。
 * 维护注意：先验证租户并锁订单，再按稳定顺序锁卡；认领失败应整体回滚。商家查询须包含merchantId，禁止把通用按SKU查询直接暴露为跨店读取入口。全额退款作废只允许 status=1→2，不删除卡密、不回退库存。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ProductVirtualCard;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProductVirtualCardMapper extends BaseMapper<ProductVirtualCard> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM product_virtual_card WHERE sku_id = #{skuId} "
            + "AND product_id = #{productId} AND merchant_id = #{merchantId} AND status = 0 "
            + "ORDER BY id LIMIT #{quantity} FOR UPDATE")
    List<ProductVirtualCard> lockAvailableCards(@org.apache.ibatis.annotations.Param("skuId") Long skuId,
            @org.apache.ibatis.annotations.Param("productId") Long productId,
            @org.apache.ibatis.annotations.Param("merchantId") Long merchantId,
            @org.apache.ibatis.annotations.Param("quantity") int quantity);

    @org.apache.ibatis.annotations.Update("UPDATE product_virtual_card SET status = 1, order_sn = #{orderSn} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND sku_id = #{skuId} AND status = 0")
    int claimCard(@org.apache.ibatis.annotations.Param("id") Long id,
                  @org.apache.ibatis.annotations.Param("merchantId") Long merchantId,
                  @org.apache.ibatis.annotations.Param("skuId") Long skuId,
                  @org.apache.ibatis.annotations.Param("orderSn") String orderSn);

    /**
     * T40 退款默认口径：虚拟卡密订单全额退款成功即作废该订单已售卡密（status 1→2）。
     * 条件更新天然幂等；卡密正文（密文）保留用于倒查，不删除、不恢复未售库存。
     */
    @org.apache.ibatis.annotations.Update("UPDATE product_virtual_card SET status = 2 "
            + "WHERE order_sn = #{orderSn} AND merchant_id = #{merchantId} AND status = 1")
    int voidSoldCardsByOrderSn(@org.apache.ibatis.annotations.Param("orderSn") String orderSn,
                               @org.apache.ibatis.annotations.Param("merchantId") Long merchantId);

    default List<ProductVirtualCard> findAvailableForMerchant(Long skuId, Long merchantId) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getSkuId, skuId)
                .eq(ProductVirtualCard::getMerchantId, merchantId)
                .eq(ProductVirtualCard::getStatus, 0));
    }

    default Optional<ProductVirtualCard> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    default ProductVirtualCard save(ProductVirtualCard entity) {
        if (entity.getId() == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<ProductVirtualCard> findBySkuIdAndStatus(Long skuId, Integer status) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getSkuId, skuId)
                .eq(ProductVirtualCard::getStatus, status));
    }

    default List<ProductVirtualCard> findByMerchantId(Long merchantId) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getMerchantId, merchantId));
    }

    /**
     * 商家卡密分页/限量读取：必须带 merchantId；beforeId 为 keyset 游标，limit 由调用方校验上限。
     * 仅用于脱敏列表与店长授权查看/导出，不向公开接口暴露。
     */
    default List<ProductVirtualCard> findMerchantCards(Long merchantId, Long skuId, boolean onlyAvailable,
                                                      Long beforeId, int limit) {
        LambdaQueryWrapper<ProductVirtualCard> query = new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getMerchantId, merchantId)
                .eq(skuId != null, ProductVirtualCard::getSkuId, skuId)
                .eq(onlyAvailable, ProductVirtualCard::getStatus, 0)
                .lt(beforeId != null, ProductVirtualCard::getId, beforeId)
                .orderByDesc(ProductVirtualCard::getId)
                .last("LIMIT " + limit);
        return selectList(query);
    }

    default List<ProductVirtualCard> findByOrderSn(String orderSn) {
        return selectList(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getOrderSn, orderSn));
    }

    default long countBySkuIdAndStatus(Long skuId, Integer status) {
        return selectCount(new LambdaQueryWrapper<ProductVirtualCard>()
                .eq(ProductVirtualCard::getSkuId, skuId)
                .eq(ProductVirtualCard::getStatus, status));
    }
}

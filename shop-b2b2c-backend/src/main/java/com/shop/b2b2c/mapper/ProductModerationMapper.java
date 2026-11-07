/*
 * 文件：ProductModerationMapper.java
 * 用途：F11 平台商品违规处置持久化：管理端分页查询（join 店铺名）、违规下架/审核恢复的 CAS 状态更新与审计写入。
 * 维护注意：
 *  1. takedownCas/restoreCas 必须用带状态条件的 UPDATE（compare-and-set），0 行表示并发已变化，服务层转 409；
 *     禁止先读后写不设条件，否则并发处置会互相覆盖且审计 from_status 失真。
 *  2. takedownCas 的 WHERE 含 status=#{fromStatus}（服务层先读到 0/1 才允许发起），保证审计 from_status 与实际一致。
 *  3. restoreCas 固定从 status=2 恢复并清空 violation_reason/violated_at；恢复目标状态由服务层取最近一次 TAKEDOWN 的 from_status。
 *  4. 审计表只增不改；列表查询 LOCATE 参数化匹配标题，不拼接字符串。
 */
package com.shop.b2b2c.mapper;

import com.shop.b2b2c.dto.ProductModerationDTOs;
import com.shop.b2b2c.entity.Product;
import com.shop.b2b2c.entity.ProductViolationAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductModerationMapper {

    @Select("SELECT * FROM product WHERE id=#{id}")
    Product findById(@Param("id") Long id);

    @Select("""
        <script>
        SELECT p.id, p.merchant_id, s.store_name, p.category_id, p.title, p.sub_title, p.goods_type,
               p.main_image, p.min_price, p.status, p.violation_reason, p.violated_at, p.updated_at
        FROM product p
        JOIN merchant_store s ON s.id = p.merchant_id
        WHERE 1=1
        <if test="merchantId != null">AND p.merchant_id = #{merchantId}</if>
        <if test="status != null">AND p.status = #{status}</if>
        <if test="keyword != null">AND LOCATE(LOWER(#{keyword}), LOWER(p.title)) &gt; 0</if>
        ORDER BY p.id DESC
        LIMIT #{size} OFFSET #{offset}
        </script>
        """)
    List<ProductModerationDTOs.AdminProductRow> listAdminProducts(@Param("merchantId") Long merchantId,
                                                                  @Param("status") Integer status,
                                                                  @Param("keyword") String keyword,
                                                                  @Param("offset") int offset,
                                                                  @Param("size") int size);

    @Select("""
        <script>
        SELECT COUNT(*)
        FROM product p
        WHERE 1=1
        <if test="merchantId != null">AND p.merchant_id = #{merchantId}</if>
        <if test="status != null">AND p.status = #{status}</if>
        <if test="keyword != null">AND LOCATE(LOWER(#{keyword}), LOWER(p.title)) &gt; 0</if>
        </script>
        """)
    long countAdminProducts(@Param("merchantId") Long merchantId,
                            @Param("status") Integer status,
                            @Param("keyword") String keyword);

    @Update("UPDATE product SET status=2, violation_reason=#{reason}, violated_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status=#{fromStatus}")
    int takedownCas(@Param("id") Long id, @Param("fromStatus") int fromStatus, @Param("reason") String reason);

    @Update("UPDATE product SET status=#{toStatus}, violation_reason=NULL, violated_at=NULL, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status=2")
    int restoreCas(@Param("id") Long id, @Param("toStatus") int toStatus);

    @Insert("INSERT INTO product_violation_audit(product_id, merchant_id, action, from_status, to_status, reason, operator_id) "
            + "VALUES(#{audit.productId}, #{audit.merchantId}, #{audit.action}, #{audit.fromStatus}, #{audit.toStatus}, "
            + "#{audit.reason}, #{audit.operatorId})")
    @Options(useGeneratedKeys = true, keyProperty = "audit.id", keyColumn = "id")
    int insertAudit(@Param("audit") ProductViolationAudit audit);

    /** 最近一次违规下架记录（RESTORE 用于取回原始状态）。 */
    @Select("SELECT * FROM product_violation_audit WHERE product_id=#{id} AND action='TAKEDOWN' "
            + "ORDER BY id DESC LIMIT 1")
    ProductViolationAudit latestTakedown(@Param("id") Long id);

    @Select("SELECT * FROM product_violation_audit WHERE product_id=#{id} ORDER BY id DESC LIMIT #{limit}")
    List<ProductViolationAudit> listAudits(@Param("id") Long id, @Param("limit") int limit);
}

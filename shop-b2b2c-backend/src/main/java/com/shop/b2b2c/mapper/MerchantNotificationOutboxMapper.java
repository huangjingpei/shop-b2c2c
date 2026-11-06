/*
 * 文件：MerchantNotificationOutboxMapper.java
 * 用途：O8 通知发件箱持久化与站内收件箱查询（商家端按 MERCHANT:{id}、管理端按角色/全部）。
 * 维护注意：
 * 1. 幂等靠 uk_notification_event 唯一约束；服务层只对 DuplicateKeyException 做“已通知”判定。
 * 2. 站内收件箱只返回 channel=INAPP 且 status=SENT 的记录；PENDING/SKIPPED 是通道排队/禁用，不对用户展示。
 * 3. 查询按 id DESC 限量返回，不做分页深翻；运营看板统计属后续卡片，不在本类扩展。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.MerchantNotificationOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MerchantNotificationOutboxMapper extends BaseMapper<MerchantNotificationOutbox> {

    /** 商家站内收件箱：本店已送达的 INAPP 通知。 */
    @Select("SELECT * FROM merchant_notification_outbox "
            + "WHERE recipient = #{recipient} AND channel = 'INAPP' AND status = 'SENT' "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<MerchantNotificationOutbox> findInappByRecipient(@Param("recipient") String recipient,
                                                          @Param("limit") int limit);

    /** 管理端通知列表：按业务类型/状态过滤的最新记录（含 ROLE:SUPERADMIN 的 SLA 提醒）。 */
    @Select("<script>SELECT * FROM merchant_notification_outbox "
            + "<where>"
            + "<if test='bizType != null and bizType != \"\"'>AND biz_type = #{bizType}</if>"
            + "<if test='status != null and status != \"\"'>AND status = #{status}</if>"
            + "</where>"
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    List<MerchantNotificationOutbox> findRecentForAdmin(@Param("bizType") String bizType,
                                                        @Param("status") String status,
                                                        @Param("limit") int limit);
}

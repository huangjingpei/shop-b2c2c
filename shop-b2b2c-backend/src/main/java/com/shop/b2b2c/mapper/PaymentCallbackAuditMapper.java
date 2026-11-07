/*
 * 文件：PaymentCallbackAuditMapper.java
 * 用途：F01 支付回调审计数据访问；当前只提供插入能力，供网关在每次回调后留痕。
 * 维护注意：
 *  1. 审计表只增不改，本 Mapper 刻意不暴露 update/delete 的业务封装，避免把审计行改成“事后结论”。
 *  2. 不在此处添加跨表 JOIN 或按订单聚合查询；查询需另建只读接口并走白名单字段，避免回传回调指纹以外的敏感内容。
 *  3. 写入失败不得影响支付结果，调用方必须捕获异常并记录 WARN，不得把审计故障当作支付失败返回渠道。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.PaymentCallbackAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentCallbackAuditMapper extends BaseMapper<PaymentCallbackAudit> {
}

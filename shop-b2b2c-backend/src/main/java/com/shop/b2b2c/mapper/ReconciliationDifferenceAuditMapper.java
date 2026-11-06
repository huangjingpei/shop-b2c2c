/*
 * 文件：ReconciliationDifferenceAuditMapper.java
 * 用途：对账差异处理审计的只追加数据访问。
 * 维护注意：只允许 insert；不得提供 update/delete 的便捷方法，避免历史被覆盖。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ReconciliationDifferenceAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReconciliationDifferenceAuditMapper extends BaseMapper<ReconciliationDifferenceAudit> {
}

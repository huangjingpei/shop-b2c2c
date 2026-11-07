/*
 * 文件：ComplaintEventMapper.java
 * 用途：F06 complaint_event 追加表持久化：只插入与按工单顺序读取，不提供更新/删除。
 * 维护注意：事件记录是审计快照，任何业务代码不得 update/deleteById；读取时按 created_at+id 升序保证时序稳定。
 */
package com.shop.b2b2c.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.b2b2c.entity.ComplaintEvent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ComplaintEventMapper extends BaseMapper<ComplaintEvent> {

    default List<ComplaintEvent> findByComplaintIdOrderByCreatedAt(Long complaintId) {
        return selectList(new LambdaQueryWrapper<ComplaintEvent>()
                .eq(ComplaintEvent::getComplaintId, complaintId)
                .orderByAsc(ComplaintEvent::getCreatedAt)
                .orderByAsc(ComplaintEvent::getId));
    }
}

/*
 * 文件：IdempotencyMapper.java
 * 用途：通过唯一键占位及行锁串行化建单或人工库存调整请求，以及按保留期清理历史快照。
 * 维护注意：沿用order_idempotency表名，调用方必须使用不同业务/身份作用域；MySQL兼容upsert只做无副作用更新以等待并发提交，不能覆盖原参数摘要。所有方法须在同一事务内调用，不依赖影响行数判定首次请求。
 * deleteExpiredBefore 只删除 response_json 已完成（非空）的过期记录，保留未完成占位避免重试窗口被截断。
 */
package com.shop.b2b2c.mapper;
import com.shop.b2b2c.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
@Mapper
public interface IdempotencyMapper {
    @Insert("INSERT INTO order_idempotency(request_key,request_hash) VALUES(#{key},#{hash}) "
            + "ON DUPLICATE KEY UPDATE request_key=request_key")
    void claim(@Param("key") String key, @Param("hash") String hash);
    @Select("SELECT request_hash,response_json FROM order_idempotency WHERE request_key=#{key} FOR UPDATE")
    IdempotencyRecord lock(@Param("key") String key);
    @Update("UPDATE order_idempotency SET response_json=#{json} WHERE request_key=#{key} AND response_json IS NULL")
    int complete(@Param("key") String key, @Param("json") String json);
    /**
     * R27：按保留期清除已完成的历史幂等快照。
     * 仅删除 response_json 非空（已完成）且 created_at 早于截止时间的记录，
     * 未完成的占位记录（response_json IS NULL）不清除，以保护并发重试窗口。
     * limit 防止单批删除影响过大；批量调用由调度任务按游标驱动。
     */
    @Delete("DELETE FROM order_idempotency WHERE response_json IS NOT NULL AND created_at < #{before} LIMIT #{limit}")
    int deleteExpiredBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}

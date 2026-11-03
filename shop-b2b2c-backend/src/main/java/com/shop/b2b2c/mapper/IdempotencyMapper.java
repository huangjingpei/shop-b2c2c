/*
 * 文件：IdempotencyMapper.java
 * 用途：通过唯一键占位及行锁串行化建单或人工库存调整请求。
 * 维护注意：沿用order_idempotency表名，调用方必须使用不同业务/身份作用域；MySQL兼容upsert只做无副作用更新以等待并发提交，不能覆盖原参数摘要。所有方法须在同一事务内调用，不依赖影响行数判定首次请求。
 */
package com.shop.b2b2c.mapper;
import com.shop.b2b2c.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.*;
@Mapper
public interface IdempotencyMapper {
    @Insert("INSERT INTO order_idempotency(request_key,request_hash) VALUES(#{key},#{hash}) "
            + "ON DUPLICATE KEY UPDATE request_key=request_key")
    void claim(@Param("key") String key, @Param("hash") String hash);
    @Select("SELECT request_hash,response_json FROM order_idempotency WHERE request_key=#{key} FOR UPDATE")
    IdempotencyRecord lock(@Param("key") String key);
    @Update("UPDATE order_idempotency SET response_json=#{json} WHERE request_key=#{key} AND response_json IS NULL")
    int complete(@Param("key") String key, @Param("json") String json);
}

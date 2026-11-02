/*
 * 文件：Category.java
 * 用途：平台商品类目的持久化模型。
 * 维护注意：parentId表示层级关系；完整树校验、循环关系及删除影响需业务层处理，不能仅凭save保证类目有效。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("category")
public class Category {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId = 0L;
    private String name;
    private String icon;
    private Integer sortOrder = 0;
    private LocalDateTime createdAt;
}

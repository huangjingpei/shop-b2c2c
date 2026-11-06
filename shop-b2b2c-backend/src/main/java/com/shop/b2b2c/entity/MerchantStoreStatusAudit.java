/*
 * 文件：MerchantStoreStatusAudit.java
 * 用途：O6 店铺经营生命周期状态流转审计：冻结/解封/关店/注销/打烊/恢复全留痕，append-only。
 * 维护注意：
 * 1. 只追加不修改；from/to 状态语义：0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)。
 * 2. operatorId 为空表示系统任务（如资质过期自动冻结）；confirmedBy 为双人复核人，
 *    approvalMode 记录 SAME_OPERATOR（非严格模式同人放行，必须同步 WARN 日志）或 DUAL。
 * 3. action 取值 OPEN/PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL；新增动作先更新开发文档附录 A.5。
 * 4. 审计与 merchant_store.status 的 CAS 更新必须在同一事务写入，禁止只有状态没有审计。
 */
package com.shop.b2b2c.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant_store_status_audit")
public class MerchantStoreStatusAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long merchantId;

    /** OPEN/PAUSE/RESUME/FREEZE/UNFREEZE/CLOSE/CANCEL。 */
    private String action;

    private Integer fromStatus;

    /** 0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)。 */
    private Integer toStatus;

    /** 原因码（附录 A.5）；OTHER 时 reason 必填。 */
    private String reasonCode;

    private String reason;

    /** 为空表示系统任务。 */
    private Long operatorId;

    /** 双人复核人（解封/注销建议必填）。 */
    private Long confirmedBy;

    /** SAME_OPERATOR / DUAL_APPROVAL。 */
    private String approvalMode;

    private LocalDateTime createdAt;
}

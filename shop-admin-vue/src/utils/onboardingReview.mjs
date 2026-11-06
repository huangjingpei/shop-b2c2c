/*
 * 文件：onboardingReview.mjs
 * 用途：O7 开店审核工作台的前端共享规则：驳回/补件原因码字典与校验、初始费率口径（0 或 100–1000bps，
 *      0 必须填推广原因）、审核动作请求体组装、审核 SLA（默认 48h）超时标记与复核阶段文案。
 * 维护注意：
 * 1. 本模块只做前端提示与参数预检；动作合法性、状态机、双人复核与费率范围最终以后端
 *    MerchantOnboardingService / FinanceApprovalGuard / MerchantFeePolicyService 判定为准，不得在前端绕过。
 * 2. 原因码字典必须与后端 REJECT_CODES 完全一致；新增原因码先改 docs/2026-11-07-merchant-onboarding-development-spec.md
 *    附录 A 与后端字典，再同步本模块与测试。
 * 3. 费率单位恒为万分比（bps）：1%=100；提交 undefined 表示使用平台默认费率，0 仅推广期且必须携带 promotionReason。
 * 4. SLA 只是列表提醒/筛选标记，不阻断审核操作；超时口径以提交时间 submittedAt 计算。
 */

/** 驳回/补件原因码字典（与后端 REJECT_CODES 对齐，开发文档 5.5）。 */
export const REJECT_CODES = [
  { code: 'SUBJECT_INFO_MISMATCH', label: '主体信息与材料不一致' },
  { code: 'NAME_ID_MISMATCH', label: '姓名与身份证号不一致' },
  { code: 'FACE_VERIFY_FAILED', label: '实人认证未通过' },
  { code: 'ID_CARD_INVALID', label: '身份证无效' },
  { code: 'ID_CARD_EXPIRED', label: '身份证已过期' },
  { code: 'LICENSE_INVALID', label: '营业执照无效' },
  { code: 'LICENSE_EXPIRED', label: '营业执照已过期' },
  { code: 'LICENSE_SUBJECT_MISMATCH', label: '执照与主体不一致' },
  { code: 'STORE_NAME_VIOLATION', label: '店铺名称违规' },
  { code: 'MATERIAL_UNCLEAR', label: '材料不清晰' },
  { code: 'MATERIAL_MISSING', label: '必需材料缺失' },
  { code: 'DUPLICATE_SUBJECT', label: '主体重复申请' },
  { code: 'CATEGORY_NOT_SUPPORTED', label: '经营类目暂不支持' },
  { code: 'RISK_CONTROL', label: '风控原因' },
  { code: 'OTHER', label: '其他（必须填写说明）' }
]
export const REJECT_CODE_VALUES = REJECT_CODES.map(item => item.code)
export const REJECT_CODE_OTHER = 'OTHER'

/** 平台服务费费率口径：0 或 100–1000bps（1%–10%）。 */
export const RATE_MIN_BPS = 100
export const RATE_MAX_BPS = 1000
export const RATE_PROMOTION_BPS = 0

/** 审核 SLA：提交超过该小时数仍未审结（SUBMITTED/REVIEWING）即标记超时。 */
export const REVIEW_SLA_HOURS = 48

/** 需要补充/复核说明文本的动作集合；REJECT 与 SUPPLEMENT_REQUIRED 必须带原因码。 */
export const REJECT_ACTIONS = ['REJECT', 'SUPPLEMENT_REQUIRED']

/**
 * 校验初始费率输入。
 * @returns {{ok: boolean, message: string}}
 */
export function validateRateBps(rateBps, promotionReason) {
  if (rateBps === undefined || rateBps === null || rateBps === '') {
    return { ok: true, message: '' }
  }
  const value = Number(rateBps)
  if (!Number.isInteger(value)) {
    return { ok: false, message: '费率必须为整数万分比' }
  }
  if (value === RATE_PROMOTION_BPS) {
    return (promotionReason || '').trim()
      ? { ok: true, message: '' }
      : { ok: false, message: '0% 仅限推广期，必须填写推广原因' }
  }
  if (value < RATE_MIN_BPS || value > RATE_MAX_BPS) {
    return { ok: false, message: `费率只支持 0（推广）或 ${RATE_MIN_BPS}–${RATE_MAX_BPS}（1%–10%）` }
  }
  return { ok: true, message: '' }
}

/**
 * 校验驳回/补件动作的原因码与说明。
 * @returns {{ok: boolean, message: string}}
 */
export function validateRejectPayload({ action, rejectCode, reason }) {
  if (!REJECT_ACTIONS.includes(action)) {
    return { ok: false, message: '不支持的原因码动作' }
  }
  if (!rejectCode || !REJECT_CODE_VALUES.includes(rejectCode)) {
    return { ok: false, message: '请选择原因码' }
  }
  if (rejectCode === REJECT_CODE_OTHER && !(reason || '').trim()) {
    return { ok: false, message: '原因码为“其他”时必须填写审核意见' }
  }
  return { ok: true, message: '' }
}

/**
 * 组装审核动作请求体：剔除 undefined/空串，保持与后端 ReviewRequest 字段契约一致。
 * rejectCode 仅 REJECT/SUPPLEMENT_REQUIRED 携带；rateBps/promotionReason/rateConfirmedBy 仅 APPROVE 携带。
 */
export function buildReviewPayload({ action, reason, rejectCode, storeName, rateBps, promotionReason, rateConfirmedBy }) {
  const payload = { action }
  const trimmedReason = (reason || '').trim()
  if (trimmedReason) payload.reason = trimmedReason
  if (REJECT_ACTIONS.includes(action) && rejectCode) payload.rejectCode = rejectCode
  if (action === 'APPROVE') {
    const trimmedStoreName = (storeName || '').trim()
    if (trimmedStoreName) payload.storeName = trimmedStoreName
    if (rateBps !== undefined && rateBps !== null && rateBps !== '') payload.rateBps = Number(rateBps)
    const trimmedPromotion = (promotionReason || '').trim()
    if (trimmedPromotion) payload.promotionReason = trimmedPromotion
    if (rateConfirmedBy !== undefined && rateConfirmedBy !== null && rateConfirmedBy !== '') {
      payload.rateConfirmedBy = rateConfirmedBy
    }
  }
  return payload
}

/**
 * 审核是否超时：仍处于待审（SUBMITTED/REVIEWING）且提交时间早于 now - slaHours。
 * submittedAt 缺失或无法解析时不标记，避免误报。
 */
export function isSlaBreach(application, now = new Date(), slaHours = REVIEW_SLA_HOURS) {
  if (!application || !['SUBMITTED', 'REVIEWING'].includes(application.status)) return false
  const submittedAt = Date.parse(application.submittedAt)
  if (Number.isNaN(submittedAt)) return false
  const current = now instanceof Date ? now.getTime() : Date.parse(now)
  if (Number.isNaN(current)) return false
  return submittedAt <= current - slaHours * 3600 * 1000
}

/** 复核阶段文案；后端 review_stage：NONE/PRIMARY/SECONDARY/PASSED。 */
export function reviewStageText(stage) {
  return {
    NONE: '未进入审核',
    PRIMARY: '初审中',
    SECONDARY: '待复审',
    PASSED: '终审通过'
  }[stage] || stage || '-'
}

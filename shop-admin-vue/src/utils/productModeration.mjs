/*
 * 文件：productModeration.mjs
 * 用途：F11 平台商品违规处置（违规下架/审核恢复）的前端纯规则：入参装配校验与操作可用性判断。
 * 维护注意：纯函数不处理授权；reason 必填 trim 后 1..255，note 可选 ≤255；长度口径与后端 ProductModerationService 一致。
 * 违规下架允许从 0(已下架)/1(在售) 发起，恢复仅允许 2(违规下架)；状态码变化须同步后端与 productEditing.mjs。
 */

export const MODERATION_REASON_MAX = 255

/** 违规下架请求体；reason 必填，trim 后 1..255。非法时抛 Error，由页面提示。 */
export function takedownPayload(input) {
  const reason = String(input?.reason ?? '').trim()
  if (!reason) throw new Error('请填写违规处置原因')
  if (reason.length > MODERATION_REASON_MAX) throw new Error(`处置原因最长 ${MODERATION_REASON_MAX} 个字符`)
  return { reason }
}

/** 审核恢复请求体；note 可选，trim 后 ≤255，空 note 返回 {}（后端写默认恢复说明）。 */
export function restorePayload(input) {
  const note = String(input?.note ?? '').trim()
  if (note.length > MODERATION_REASON_MAX) throw new Error(`审核备注最长 ${MODERATION_REASON_MAX} 个字符`)
  return note ? { note } : {}
}

/** 违规下架按钮可用性：仅 0(已下架)/1(在售) 可发起；2 已是违规下架。 */
export function canTakedown(status) {
  return status === 0 || status === 1
}

/** 审核恢复按钮可用性：仅 2(违规下架) 可恢复。 */
export function canRestore(status) {
  return status === 2
}

/** 处置动作展示文案。 */
export function moderationActionLabel(action) {
  return ({ TAKEDOWN: '违规下架', RESTORE: '审核恢复' })[action] ?? '未知动作'
}

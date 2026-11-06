/*
 * 文件：financeReview.mjs
 * 用途：财务双人复核的前端选择与初筛规则（调价、开店初始费率、结算确认、充值确认共用）。
 * 维护注意：
 *  1. 本模块只做前端提示与参数校验，最终是否允许同人/缺复核人由后端 FinanceApprovalGuard 判定，不得在前端绕过。
 *  2. 复核人候选必须排除当前操作人；严格模式没有第二个账号时给出“需至少分配两个财务账号”的明确提示。
 *  3. 宽松模式允许不选复核人，但请求会按当前操作人记录同人复核（SAME_OPERATOR），仅限开发/试点。
 *  4. id 兼容后端 Long 的字符串序列化，比较一律用 String，不要用 === 直接比较数字与字符串。
 */
export const NO_SECOND_ACCOUNT_MESSAGE = '需至少分配两个财务账号后才能执行双人复核'

export const REVIEWER_ERROR = Object.freeze({
  NO_SECOND_ACCOUNT: 'NO_SECOND_ACCOUNT',
  REVIEWER_REQUIRED: 'REVIEWER_REQUIRED',
  SELF_REVIEWER: 'SELF_REVIEWER',
  UNKNOWN_REVIEWER: 'UNKNOWN_REVIEWER'
})

function normalizedId(value) {
  if (value === null || value === undefined || String(value).trim() === '') {
    return ''
  }
  return String(value)
}

/** 过滤出可选的复核人：必须有 id，且不能是当前操作人。 */
export function reviewerOptions(reviewers, currentUserId) {
  const selfId = normalizedId(currentUserId)
  return (Array.isArray(reviewers) ? reviewers : [])
    .filter(item => item && normalizedId(item.id) !== '')
    .filter(item => normalizedId(item.id) !== selfId)
}

/** 页面展示用状态：是否严格模式、候选列表、是否阻断、无第二账号提示。 */
export function reviewerState({ dualApprovalRequired, reviewers, currentUserId }) {
  const candidates = reviewerOptions(reviewers, currentUserId)
  const strict = Boolean(dualApprovalRequired)
  return {
    dualApprovalRequired: strict,
    candidates,
    blocked: strict && candidates.length === 0,
    hasSecondAccount: candidates.length > 0,
    notice: candidates.length === 0 ? NO_SECOND_ACCOUNT_MESSAGE : ''
  }
}

/**
 * 校验本次提交的复核人选择。
 * @returns {{ok: boolean, code: string, confirmedBy: (Long|string|null), message: string}}
 */
export function validateReviewer({ dualApprovalRequired, reviewers, currentUserId, reviewerId }) {
  const state = reviewerState({ dualApprovalRequired, reviewers, currentUserId })
  if (state.blocked) {
    return {
      ok: false,
      code: REVIEWER_ERROR.NO_SECOND_ACCOUNT,
      confirmedBy: null,
      message: NO_SECOND_ACCOUNT_MESSAGE
    }
  }
  const selectedId = normalizedId(reviewerId)
  if (selectedId === '') {
    if (state.dualApprovalRequired) {
      return {
        ok: false,
        code: REVIEWER_ERROR.REVIEWER_REQUIRED,
        confirmedBy: null,
        message: '请选择复核人'
      }
    }
    return {
      ok: true,
      code: 'SAME_OPERATOR',
      confirmedBy: null,
      message: state.notice || '未选择复核人，将按当前操作人记录同人复核（仅开发/试点）'
    }
  }
  if (selectedId === normalizedId(currentUserId)) {
    return {
      ok: false,
      code: REVIEWER_ERROR.SELF_REVIEWER,
      confirmedBy: null,
      message: '复核人不能是当前操作人'
    }
  }
  if (!state.candidates.some(item => normalizedId(item.id) === selectedId)) {
    return {
      ok: false,
      code: REVIEWER_ERROR.UNKNOWN_REVIEWER,
      confirmedBy: null,
      message: '复核人必须是除自己以外的启用管理员'
    }
  }
  return {
    ok: true,
    code: 'DUAL_APPROVAL',
    confirmedBy: reviewerId,
    message: ''
  }
}

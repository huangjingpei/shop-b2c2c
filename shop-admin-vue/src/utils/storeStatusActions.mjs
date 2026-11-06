/*
 * 文件：storeStatusActions.mjs
 * 用途：O6 店铺状态机（冻结/解封/关店/注销）管理端动作可用性、原因码字典与提交前校验的纯函数。
 * 维护注意：
 *  1. 本模块只做前端展示与初筛；状态迁移、原因码合法性、双人复核最终以后端
 *     MerchantStoreStatusService / FinanceApprovalGuard 判定为准，前端不得绕过。
 *  2. 状态语义：0-待开通 1-营业 2-打烊 3-冻结 4-已注销(终态)；打烊/恢复仍走旧
 *     PUT /status 开关，本模块只覆盖 FREEZE/UNFREEZE/CLOSE/CANCEL 四类带原因码动作。
 *  3. 原因码字典必须与后端 STORE_REASON_CODES 全量一致；新增原因码先更新开发文档附录 A.5。
 *  4. 双人复核仅解封（UNFREEZE）强制；关店/注销传复核人时后端强制换人校验，不传不强制。
 */
export const STORE_STATUS = Object.freeze({
  PENDING_OPEN: 0,
  OPEN: 1,
  PAUSED: 2,
  FROZEN: 3,
  CANCELLED: 4
})

export const STORE_STATUS_ACTIONS = Object.freeze({
  FREEZE: 'FREEZE',
  UNFREEZE: 'UNFREEZE',
  CLOSE: 'CLOSE',
  CANCEL: 'CANCEL'
})

export const STORE_ACTION_LABELS = Object.freeze({
  FREEZE: '冻结（停新交易与出账）',
  UNFREEZE: '解封（双人复核）',
  CLOSE: '关店（商家退出，终态）',
  CANCEL: '注销（平台强制，终态）'
})

/** 与后端 MerchantStoreStatusService.STORE_REASON_CODES 一致；OTHER 时必须填写具体原因。 */
export const STORE_REASON_CODES = Object.freeze([
  { value: 'VIOLATION', label: '违规经营' },
  { value: 'RISK_COMPLAINT', label: '投诉风控' },
  { value: 'ARREARS', label: '欠费未缴' },
  { value: 'EXPIRED_LICENSE', label: '证照过期' },
  { value: 'MERCHANT_REQUEST', label: '商家申请' },
  { value: 'QUALIFICATION_EXPIRED', label: '资质过期' },
  { value: 'STRATEGY_ADJUST', label: '策略调整' },
  { value: 'ILLEGAL_OPERATION', label: '非法经营' },
  { value: 'REPEATED_VIOLATION', label: '多次违规' },
  { value: 'SUSPECTED_RISK', label: '疑似风险' },
  { value: 'OTHER', label: '其他' }
])

const ACTIONS_REQUIRING_REASON = new Set(Object.values(STORE_STATUS_ACTIONS))

/**
 * 按当前店铺状态返回可发起的带原因码动作。
 * 冻结/关店/注销仅营业(1)或打烊(2)；解封仅冻结(3)；待开通(0)与已注销(4)终态无可发起动作。
 */
export function availableStatusActions(status) {
  if (status === STORE_STATUS.OPEN || status === STORE_STATUS.PAUSED) {
    return [STORE_STATUS_ACTIONS.FREEZE, STORE_STATUS_ACTIONS.CLOSE, STORE_STATUS_ACTIONS.CANCEL]
  }
  if (status === STORE_STATUS.FROZEN) {
    return [STORE_STATUS_ACTIONS.UNFREEZE]
  }
  return []
}

/** 该动作是否强制双人复核（仅解封；关店/注销建议双人但由后端按是否传复核人判定）。 */
export function requiresDualApproval(action) {
  return action === STORE_STATUS_ACTIONS.UNFREEZE
}

/**
 * 提交前校验并生成 /store-status 请求体。
 * @returns {{ok: boolean, message: string, payload: ?{action: string, reasonCode: string, reason: ?string}}}
 */
export function validateStoreStatusAction({ action, reasonCode, reason }) {
  if (!ACTIONS_REQUIRING_REASON.has(action)) {
    return { ok: false, message: '不支持的店铺状态操作', payload: null }
  }
  const code = typeof reasonCode === 'string' ? reasonCode.trim() : ''
  if (!STORE_REASON_CODES.some(item => item.value === code)) {
    return { ok: false, message: '请选择合法原因码', payload: null }
  }
  const text = typeof reason === 'string' ? reason.trim() : ''
  if (code === 'OTHER' && text === '') {
    return { ok: false, message: '原因码为其他时必须填写具体原因', payload: null }
  }
  if (text.length > 512) {
    return { ok: false, message: '原因说明不能超过 512 字', payload: null }
  }
  return {
    ok: true,
    message: '',
    payload: { action, reasonCode: code, reason: text === '' ? null : text }
  }
}

/** 审计时间线展示文案：动作 + 原因码中文名 + 复核方式。 */
export function auditView(item) {
  const action = item && typeof item.action === 'string' ? item.action : ''
  const reason = STORE_REASON_CODES.find(code => code.value === (item && item.reasonCode))
  return {
    actionLabel: STORE_ACTION_LABELS[action] || action,
    reasonLabel: reason ? `${reason.value}（${reason.label}）` : (item && item.reasonCode) || '',
    approvalLabel: item && item.approvalMode === 'DUAL_APPROVAL' ? '双人复核' : '同人记录',
    operatorLabel: item && item.operatorId != null ? `操作人 #${item.operatorId}` : '系统任务',
    confirmedByLabel: item && item.confirmedBy != null ? `复核人 #${item.confirmedBy}` : ''
  }
}

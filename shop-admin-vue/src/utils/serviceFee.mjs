/*
 * 文件：serviceFee.mjs
 * 用途：统一服务费配置状态展示、编辑回填和请求字段构造。
 * 维护注意：五个既有编码分别保留，不推断扣费/停服；未知值不能默认为NORMAL。日期回填从后端LocalDateTime取本地日期，空日期发送null表示不修改，不代表后端支持清除截止日。
 */
export const FEE_STATUS_OPTIONS = Object.freeze([
  Object.freeze({ value: 'NORMAL', label: '正常', type: 'info' }),
  Object.freeze({ value: 'TRIAL', label: '免费期', type: 'success' }),
  Object.freeze({ value: 'OVERDUE', label: '逾期标记', type: 'danger' }),
  Object.freeze({ value: 'EXEMPTED', label: '免收豁免', type: 'success' }),
  Object.freeze({ value: 'ARREARS', label: '欠费标记', type: 'danger' })
])

export function feeStatusView(code) {
  return FEE_STATUS_OPTIONS.find(option => option.value === code)
    ?? { value: '', label: '服务费状态未知', type: 'warning' }
}

export function serviceFeeForm(row) {
  return {
    monthlyFee: row.monthlyFee ?? 0,
    feeFreeUntil: typeof row.feeFreeUntil === 'string' ? row.feeFreeUntil.slice(0, 10) : '',
    feeStatus: feeStatusView(row.feeStatus).value
  }
}

export function serviceFeePayload(form) {
  if (!FEE_STATUS_OPTIONS.some(option => option.value === form.feeStatus)) {
    throw new Error('请选择有效的服务费状态')
  }
  return {
    monthlyFee: form.monthlyFee,
    feeFreeUntil: form.feeFreeUntil || null,
    feeStatus: form.feeStatus
  }
}

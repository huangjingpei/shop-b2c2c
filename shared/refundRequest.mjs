/*
 * 文件：refundRequest.mjs
 * 用途：F05 退款申请纯规则：金额校验（>0、≤可退余额、≤2位小数）、原因非空、状态白名单、申请/撤回 payload。
 * 维护注意：可退余额由后端最终校验，前端只做客户端预检；金额用字符串保留精度，避免浮点误差。
 */

export const REFUND_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED']
export const REFUNDABLE_ORDER_STATUSES = ['PAID', 'SHIPPED', 'COMPLETED']

export function isValidAmount (amount, maxRefundable) {
  if (amount === null || amount === undefined || amount === '') return false
  const n = Number(amount)
  if (!Number.isFinite(n) || n <= 0) return false
  if (!/^\d+(\.\d{1,2})?$/.test(String(amount).trim())) return false
  if (maxRefundable !== undefined && maxRefundable !== null) {
    if (n > Number(maxRefundable)) return false
  }
  return true
}

export function refundRequestPayload ({ orderSn, contact, queryToken, refundAmount, reason }) {
  const amount = String(refundAmount).trim()
  if (!orderSn || !orderSn.trim()) throw new Error('订单号不能为空')
  if (!contact || !contact.trim()) throw new Error('联系信息不能为空')
  if (!queryToken || !queryToken.trim()) throw new Error('查询凭证不能为空')
  if (!isValidAmount(amount)) throw new Error('退款金额必须为正数且不超过两位小数')
  if (!reason || !reason.trim()) throw new Error('退款原因不能为空')
  if (reason.trim().length > 256) throw new Error('退款原因不能超过256字')
  return {
    orderSn: orderSn.trim(),
    contact: contact.trim(),
    queryToken: queryToken.trim(),
    refundAmount: amount,
    reason: reason.trim()
  }
}

export function rejectPayload (rejectReason) {
  const r = (rejectReason || '').trim()
  if (!r) throw new Error('拒绝原因不能为空')
  if (r.length > 256) throw new Error('拒绝原因不能超过256字')
  return { rejectReason: r }
}

export function refundStatusLabel (status) {
  return { PENDING: '待商家处理', APPROVED: '已同意退款', REJECTED: '已拒绝', CANCELLED: '已撤回' }[status] || status || ''
}

export function refundStatusTag (status) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }[status] || 'info'
}

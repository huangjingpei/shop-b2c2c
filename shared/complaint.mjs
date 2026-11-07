/*
 * 文件：complaint.mjs
 * 用途：F06 投诉协商与平台仲裁的纯规则：状态/类型白名单、举证链接校验、提交/答复/裁决 payload 构造与状态文案。
 * 维护注意：所有校验仅为客户端预检，后端仍是最终判定（可退余额、工单归属、状态机）；不得在此处推导退款金额上限。
 */

export const COMPLAINT_TYPES = ['QUALITY', 'FAKE', 'SERVICE_NOT_DELIVERED', 'OTHER']
export const COMPLAINT_STATUSES = ['PENDING', 'MERCHANT_REPLIED', 'ARBITRATING', 'RESOLVED', 'REJECTED']
export const OPEN_COMPLAINT_STATUSES = ['PENDING', 'MERCHANT_REPLIED', 'ARBITRATING']
export const MAX_EVIDENCE = 5
export const MAX_EVIDENCE_URL_LENGTH = 300

export const isValidComplaintContent = content => {
  const text = (content || '').trim()
  return text.length >= 5 && text.length <= 1000
}

export const isValidEvidenceList = list => {
  if (list === null || list === undefined) return true
  if (!Array.isArray(list) || list.length > MAX_EVIDENCE) return false
  return list.every(url => {
    const value = typeof url === 'string' ? url.trim() : ''
    return value.length > 0 && value.length <= MAX_EVIDENCE_URL_LENGTH &&
      (value.startsWith('https://') || value.startsWith('http://')) && !/\s/.test(value)
  })
}

export const complaintSubmitPayload = ({ orderSn, contact, queryToken, complaintType, content, evidenceImages }) => {
  if (!orderSn || !orderSn.trim()) throw new Error('订单号不能为空')
  if (!contact || !contact.trim()) throw new Error('联系信息不能为空')
  if (!queryToken || !queryToken.trim()) throw new Error('查询凭证不能为空')
  if (!COMPLAINT_TYPES.includes(complaintType)) throw new Error('投诉类型不合法')
  if (!isValidComplaintContent(content)) throw new Error('投诉说明需 5-1000 字')
  if (!isValidEvidenceList(evidenceImages)) throw new Error('举证只接受 http/https 链接，最多 5 条')
  return {
    orderSn: orderSn.trim(),
    contact: contact.trim(),
    queryToken: queryToken.trim(),
    complaintType,
    content: content.trim(),
    evidenceImages: (evidenceImages || []).map(u => u.trim())
  }
}

export const merchantReplyPayload = ({ reply, evidenceImages }) => {
  const text = (reply || '').trim()
  if (!text) throw new Error('答复内容不能为空')
  if (text.length > 1000) throw new Error('答复内容不能超过 1000 字')
  if (!isValidEvidenceList(evidenceImages)) throw new Error('举证只接受 http/https 链接，最多 5 条')
  return { reply: text, evidenceImages: (evidenceImages || []).map(u => u.trim()) }
}

export const arbitratePayload = ({ decision, arbitration, refundAmount }) => {
  if (!['RESOLVE', 'REJECT'].includes(decision)) throw new Error('裁决结果不合法')
  const text = (arbitration || '').trim()
  if (!text) throw new Error('仲裁结论不能为空')
  if (text.length > 1000) throw new Error('仲裁结论不能超过 1000 字')
  const amount = refundAmount === null || refundAmount === undefined || refundAmount === '' ? '0' : String(refundAmount).trim()
  if (!/^\d+(\.\d{1,2})?$/.test(amount)) throw new Error('退款金额必须为非负数且最多两位小数')
  if (decision === 'REJECT' && Number(amount) > 0) throw new Error('驳回裁决不能同时退款')
  return { decision, arbitration: text, refundAmount: amount }
}

export const complaintStatusLabel = status => ({
  PENDING: '待商家答复', MERCHANT_REPLIED: '商家已答复', ARBITRATING: '平台仲裁中',
  RESOLVED: '平台已裁决', REJECTED: '平台已驳回'
}[status] || status || '')

export const complaintStatusTag = status => ({
  PENDING: 'warning', MERCHANT_REPLIED: 'primary', ARBITRATING: 'info',
  RESOLVED: 'success', REJECTED: 'danger'
}[status] || 'info')

export const complaintTypeLabel = type => ({
  QUALITY: '质量问题', FAKE: '假冒商品', SERVICE_NOT_DELIVERED: '未按约履约', OTHER: '其他'
}[type] || type || '')

export const canReplyComplaint = status => status === 'PENDING' || status === 'MERCHANT_REPLIED'
export const canEscalateComplaint = status => status === 'PENDING' || status === 'MERCHANT_REPLIED'
export const canArbitrateComplaint = status => status === 'ARBITRATING'

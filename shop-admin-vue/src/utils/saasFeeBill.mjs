/*
 * 文件：saasFeeBill.mjs
 * 用途：F10 SaaS 月费账单前端纯规则：状态文案/颜色、账期与查询参数校验、线下缴费与生成请求装配。
 * 维护注意：
 *  1. 纯函数不处理授权与金额计算；应收金额只能来自后端账单，前端不得传入或改写 amount/status。
 *  2. 账期统一 yyyy-MM；只允许对已结束月份生成账单的最终校验仍在后端，前端仅做格式与必填检查。
 *  3. 缴费方式本期仅 OFFLINE，凭证号必填且最长 64；复核人是否必填由后端 FinanceApprovalGuard 判定。
 *  4. 规则变更须同步 shared/saasFeeBill.test.mjs 与后端 SaasFeeBillService 校验口径。
 */

export const BILL_STATUSES = ['PENDING', 'PAID', 'WAIVED', 'OVERDUE']
export const PAYMENT_METHODS = ['OFFLINE']
const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/

/** 账单状态中文文案。 */
export function statusLabel(status) {
  return ({ PENDING: '待缴费', PAID: '已缴费', WAIVED: '免收', OVERDUE: '已逾期' })[status] ?? '未知状态'
}

/** 账单状态对应的 el-tag 颜色。 */
export function statusTag(status) {
  return ({ PENDING: 'warning', PAID: 'success', WAIVED: 'info', OVERDUE: 'danger' })[status] ?? 'info'
}

/** 账期月份展示：2026-10 → 2026年10月。 */
export function monthLabel(month) {
  if (!MONTH_PATTERN.test(String(month || ''))) return '未知账期'
  const [year, value] = month.split('-')
  return `${year}年${Number(value)}月`
}

/** 本地时区上一个月 yyyy-MM，用作默认筛选。 */
export function previousMonth(now = new Date()) {
  const date = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

function normalizeMerchantId(value) {
  if (value === null || value === undefined || value === '') return null
  const id = Number(value)
  if (!Number.isInteger(id) || id <= 0) throw new Error('店铺编号必须是正整数')
  return id
}

/**
 * 超管账单列表查询参数：status/month 可空；limit 1..200，offset>=0。
 * 返回与后端 GET /admin/settlement/saas-bills 一致的参数对象。
 */
export function saasBillQuery({ merchantId = null, status = null, month = null, limit = 50, offset = 0 } = {}) {
  const id = normalizeMerchantId(merchantId)
  const safeStatus = status === '' ? null : status
  if (safeStatus !== null && !BILL_STATUSES.includes(safeStatus)) throw new Error('账单状态不合法')
  const safeMonth = month === '' ? null : month
  if (safeMonth !== null && !MONTH_PATTERN.test(String(safeMonth))) throw new Error('账期月份格式应为 yyyy-MM')
  const pageSize = Number(limit)
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 200) throw new Error('每页条数须在 1 到 200 之间')
  const skip = Number(offset)
  if (!Number.isInteger(skip) || skip < 0) throw new Error('偏移量必须为非负整数')
  return {
    merchantId: id,
    status: safeStatus,
    month: safeMonth,
    limit: pageSize,
    offset: skip
  }
}

/** 手动生成单店账单请求：店铺编号 + yyyy-MM 账期。 */
export function generateBillPayload({ merchantId, month } = {}) {
  const id = normalizeMerchantId(merchantId)
  if (id === null) throw new Error('请选择店铺')
  if (!MONTH_PATTERN.test(String(month || ''))) throw new Error('账期月份格式应为 yyyy-MM')
  return { merchantId: id, month }
}

/**
 * 线下缴费确认请求：不携带金额/状态；凭证号必填最长 64，备注最长 255，复核人可选。
 * 复核人严格模式必填由后端判定，前端只负责格式。
 */
export function paymentPayload({ paymentMethod = 'OFFLINE', voucherNo, remark = '', confirmedBy = null } = {}) {
  const method = paymentMethod === '' || paymentMethod === null ? 'OFFLINE' : paymentMethod
  if (!PAYMENT_METHODS.includes(method)) throw new Error('缴费方式本期仅支持线下转账')
  const voucher = String(voucherNo || '').trim()
  if (!voucher) throw new Error('请填写线下转账凭证号')
  if (voucher.length > 64) throw new Error('凭证号最长 64 个字符')
  const note = String(remark || '').trim()
  if (note.length > 255) throw new Error('备注最长 255 个字符')
  let reviewer = null
  if (confirmedBy !== null && confirmedBy !== undefined && confirmedBy !== '') {
    reviewer = Number(confirmedBy)
    if (!Number.isInteger(reviewer) || reviewer <= 0) throw new Error('复核人编号不合法')
  }
  return { paymentMethod: method, voucherNo: voucher, remark: note, confirmedBy: reviewer }
}

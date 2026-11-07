/*
 * 文件：billingStatement.mjs
 * 用途：F09 商户账单（日/月）前端纯规则：查询参数校验、状态/类型文案与导出文件名。
 * 维护注意：纯函数不处理授权与聚合；跨度上限（日92天/月24个月）与 from≤to 口径须与后端
 * BillingStatementService 一致；金额已由后端按 DECIMAL(12,2) 返回，展示不再二次四舍五入。
 */

export const PERIOD_TYPES = ['DAILY', 'MONTHLY']
export const DAILY_SPAN_MAX = 92
export const MONTHLY_SPAN_MAX = 24

const DAY_MS = 24 * 60 * 60 * 1000

/** 账期类型文案。 */
export function periodTypeLabel(type) {
  return ({ DAILY: '日账单', MONTHLY: '月账单' })[type] ?? '未知类型'
}

/** 账单状态文案。 */
export function billingStatusLabel(status) {
  return ({ PENDING: '核算中', SETTLED: '已结算出账', DISPUTED: '有争议' })[status] ?? '未知状态'
}

/** 账单状态对应的 el-tag 类型。 */
export function billingStatusTag(status) {
  return ({ PENDING: 'info', SETTLED: 'success', DISPUTED: 'danger' })[status] ?? 'info'
}

/**
 * 组装列表/导出查询参数。from/to 为 yyyy-MM-dd 字符串；非法抛 Error，由页面提示。
 * 规则：periodType 白名单；from≤to；日账单跨度≤92天、月账单跨度≤24个月；页码 size 1..100。
 */
export function billingQuery({ periodType, from, to, page = 1, size = 20 }) {
  if (!PERIOD_TYPES.includes(periodType)) throw new Error('账期类型只能是日账单或月账单')
  if (!from || !to) throw new Error('请选择起止日期')
  const start = Date.parse(from)
  const end = Date.parse(to)
  if (Number.isNaN(start) || Number.isNaN(end)) throw new Error('日期格式不正确')
  if (start > end) throw new Error('起始日期不能晚于截止日期')
  if (periodType === 'DAILY') {
    if (Math.floor((end - start) / DAY_MS) + 1 > DAILY_SPAN_MAX) throw new Error(`日账单跨度最长 ${DAILY_SPAN_MAX} 天`)
  } else {
    const months = (new Date(to).getFullYear() - new Date(from).getFullYear()) * 12
      + (new Date(to).getMonth() - new Date(from).getMonth()) + 1
    if (months > MONTHLY_SPAN_MAX) throw new Error(`月账单跨度最长 ${MONTHLY_SPAN_MAX} 个月`)
  }
  if (!Number.isInteger(page) || page < 1) throw new Error('页码必须大于等于 1')
  if (!Number.isInteger(size) || size < 1 || size > 100) throw new Error('每页条数须在 1 到 100 之间')
  return { periodType, from, to, page, size }
}

/** 导出文件名（与后端 Content-Disposition 口径一致，仅用于展示提示）。 */
export function billingCsvFilename(shopId, periodType, from, to) {
  if (!PERIOD_TYPES.includes(periodType)) throw new Error('账期类型只能是日账单或月账单')
  const scope = shopId === null || shopId === undefined || shopId === '' ? 'all' : String(shopId)
  return `billing-${scope}-${periodType.toLowerCase()}-${from}-${to}.csv`
}

/** 重算请求校验：MONTHLY 允许传月内任意一天（后端归一到月初）。 */
export function regeneratePayload({ shopId, periodDate, periodType }) {
  const id = Number(shopId)
  if (!Number.isInteger(id) || id <= 0) throw new Error('店铺编号不正确')
  if (!PERIOD_TYPES.includes(periodType)) throw new Error('账期类型只能是日账单或月账单')
  if (!periodDate || Number.isNaN(Date.parse(periodDate))) throw new Error('账期日期必填')
  return { shopId: id, periodDate, periodType }
}

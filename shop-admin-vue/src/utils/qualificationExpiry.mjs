/*
 * 文件：qualificationExpiry.mjs
 * 用途：O9 资质有效期前端规则：日期解析/天数差/状态判定（临近/到期）、资料页展示视图、更新表单校验。
 * 维护注意：
 *  1. 纯函数不依赖后端会话；today 一律以 'YYYY-MM-DD' 传入（页面用本地日期生成），测试注入固定值。
 *  2. 状态口径与后端一致：expiry <= 今天 即 EXPIRED（到期当天限制上新）；0 < daysLeft <= 60 为 EXPIRING；
 *    有效期为空（未采集/长期）为 NONE，不提醒不拦截。
 *  3. 更新校验只是前置体验：至少一项、合法日期、必须未来日期；最终以后端接口校验为准。
 *  4. 比较用 UTC 毫秒差，避免本地时区与夏令时误差；不接受非 ISO 格式输入。
 */

/** 临近提醒阈值（天）：与后端 QualificationExpiryJob 最大提醒档 60 天对齐。 */
export const EXPIRING_DAYS = 60

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const MS_PER_DAY = 24 * 60 * 60 * 1000

/** 严格解析 'YYYY-MM-DD'：返回 UTC 毫秒时间戳；非法格式或不存在的日期返回 null。 */
export function parseExpiry(value) {
  if (typeof value !== 'string' || !ISO_DATE.test(value.trim())) return null
  const trimmed = value.trim()
  const [y, m, d] = trimmed.split('-').map(Number)
  const ms = Date.UTC(y, m - 1, d)
  const date = new Date(ms)
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null
  return ms
}

/** 到期日相对今天的天数差（>0 未到期，0 当天到期，<0 已过期）；任一输入非法返回 null。 */
export function daysUntil(expiryDate, today) {
  const expiryMs = parseExpiry(expiryDate)
  const todayMs = parseExpiry(today)
  if (expiryMs === null || todayMs === null) return null
  return Math.round((expiryMs - todayMs) / MS_PER_DAY)
}

/**
 * 资质状态：NONE-未采集；VALID-有效（>60 天）；EXPIRING-临近（0<days<=60）；EXPIRED-已到期（含当天）。
 */
export function qualificationStatus(expiryDate, today) {
  if (expiryDate === null || expiryDate === undefined || expiryDate === '') return 'NONE'
  const days = daysUntil(expiryDate, today)
  if (days === null) return 'NONE'
  if (days <= 0) return 'EXPIRED'
  return days <= EXPIRING_DAYS ? 'EXPIRING' : 'VALID'
}

const STATUS_LABELS = Object.freeze({
  NONE: '未采集',
  VALID: '有效',
  EXPIRING: '即将到期',
  EXPIRED: '已到期'
})

const STATUS_TAGS = Object.freeze({
  NONE: 'info',
  VALID: 'success',
  EXPIRING: 'warning',
  EXPIRED: 'danger'
})

/** 资料页单资质展示视图：{ name, expiry, status, label, tagType, text }。 */
export function qualificationView(expiryDate, name, today) {
  const status = qualificationStatus(expiryDate, today)
  const days = daysUntil(expiryDate, today)
  let text = '未采集有效期，证件到期前不会收到提醒'
  if (status === 'EXPIRED') {
    text = days === 0 ? '今天到期：已限制发布新商品，请立即更新有效期' : `已过期 ${-days} 天：已限制发布新商品，请立即更新有效期`
  } else if (status === 'EXPIRING') {
    text = `将于 ${days} 天后到期，请提前更新有效期`
  } else if (status === 'VALID') {
    text = `有效期至 ${expiryDate}`
  }
  return { name, expiry: expiryDate || '', status, label: STATUS_LABELS[status], tagType: STATUS_TAGS[status], text }
}

/**
 * 更新表单校验：至少一项非空；非空必须合法日期且晚于今天。
 * 返回 { valid, message, idCardExpiry, licenseExpiry }（归一化后的空白→''）。
 */
export function validateExpiryUpdate(idCardExpiry, licenseExpiry, today) {
  const idCard = (idCardExpiry || '').trim()
  const license = (licenseExpiry || '').trim()
  if (!idCard && !license) {
    return { valid: false, message: '至少提交一项资质有效期', idCardExpiry: idCard, licenseExpiry: license }
  }
  for (const [value, label] of [[idCard, '身份证有效期'], [license, '营业执照有效期']]) {
    if (!value) continue
    const ms = parseExpiry(value)
    if (ms === null) {
      return { valid: false, message: `${label}格式须为 YYYY-MM-DD 且为真实日期`, idCardExpiry: idCard, licenseExpiry: license }
    }
    const todayMs = parseExpiry(today)
    if (todayMs !== null && ms <= todayMs) {
      return { valid: false, message: `${label}必须为未来日期`, idCardExpiry: idCard, licenseExpiry: license }
    }
  }
  return { valid: true, message: '', idCardExpiry: idCard, licenseExpiry: license }
}

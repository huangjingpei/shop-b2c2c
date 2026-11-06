/*
 * 文件：notificationView.mjs
 * 用途：O8 通知发件箱前端渲染规则：payload_json 安全解析、模板标题/级别/正文行组装。
 * 维护注意：
 *  1. 只做展示层渲染，不发起任何状态变更；payload 由后端写入（敏感字段在写入侧已 fail-fast），
 *     前端不得把 payload 当作可信输入直接 v-html 渲染，只能文本插值。
 *  2. 标题/tagType 与后端 MerchantNotificationService 的模板码一一对应；新增模板先补映射再上线。
 *  3. payload_json 解析失败一律降级为空对象（展示“通知详情暂不可用”），不抛错打断列表。
 */
import { STORE_ACTION_LABELS, STORE_REASON_CODES } from './storeStatusActions.mjs'

/** 模板码 → 标题前缀。 */
export const NOTIFICATION_TITLES = Object.freeze({
  ONBOARDING_SUBMITTED: '开店申请已提交',
  ONBOARDING_SUPPLEMENT: '开店申请需补件',
  ONBOARDING_REJECTED: '开店申请被驳回',
  ONBOARDING_APPROVED: '开店申请已通过',
  ONBOARDING_OPENED: '店铺已开通',
  LICENSE_EXPIRING: '证件即将到期提醒',
  REVIEW_SLA_BREACH: '开店审核超时',
  STORE_STATUS_CHANGED: '店铺状态变更'
})

/** 审核SLA档位码 → 级别中文。 */
const SLA_LEVEL_LABELS = Object.freeze({
  REVIEW_SLA_REMINDER: '超时提醒',
  REVIEW_SLA_ESCALATION: '超时升级',
  REVIEW_SLA_REVIEWER: '复核人提醒'
})

/** payload_json 安全解析：字符串/对象均可，失败返回空对象。 */
export function parsePayload(payloadJson) {
  if (!payloadJson) return {}
  if (typeof payloadJson === 'object') return Array.isArray(payloadJson) ? {} : payloadJson
  try {
    const parsed = JSON.parse(payloadJson)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function reasonCodeLabel(code) {
  if (!code) return ''
  const hit = STORE_REASON_CODES.find(item => item.value === code)
  return hit ? `${hit.value}（${hit.label}）` : String(code)
}

/**
 * 组装一条通知的展示视图。
 * @returns {{title: string, lines: string[], tagType: 'danger'|'warning'|'success'|'info'}}
 */
export function notificationView(row) {
  const template = row?.templateCode || row?.bizType || ''
  const payload = parsePayload(row?.payloadJson)
  const title = NOTIFICATION_TITLES[template] || template || '系统通知'
  const lines = []
  let tagType = 'info'

  if (payload.storeName) lines.push(`店铺：${payload.storeName}`)
  if (payload.tip) lines.push(String(payload.tip))

  if (template === 'REVIEW_SLA_BREACH') {
    const level = SLA_LEVEL_LABELS[payload.level] || payload.level || '超时'
    tagType = payload.level === 'REVIEW_SLA_ESCALATION' ? 'danger' : 'warning'
    if (payload.waitingHours != null) lines.push(`已等待：${payload.waitingHours} 小时（${level}）`)
    if (payload.applicationId != null) lines.push(`申请单：#${payload.applicationId}`)
  } else if (template === 'STORE_STATUS_CHANGED') {
    const actionLabel = STORE_ACTION_LABELS[payload.action] || payload.action || ''
    if (actionLabel) lines.push(`动作：${actionLabel}`)
    if (payload.reasonCode) lines.push(`原因码：${reasonCodeLabel(payload.reasonCode)}`)
    if (payload.reason) lines.push(`说明：${payload.reason}`)
    tagType = payload.action === 'UNFREEZE' ? 'success' : 'danger'
  } else if (template === 'ONBOARDING_REJECTED' || template === 'ONBOARDING_SUPPLEMENT') {
    if (payload.rejectCode) lines.push(`原因码：${payload.rejectCode}`)
    if (payload.reason) lines.push(`原因：${payload.reason}`)
    tagType = template === 'ONBOARDING_REJECTED' ? 'danger' : 'warning'
  } else if (template === 'ONBOARDING_OPENED' || template === 'ONBOARDING_APPROVED') {
    if (payload.username) lines.push(`登录账号：${payload.username}`)
    if (payload.loginPath) lines.push(`登录地址：${payload.loginPath}`)
    tagType = 'success'
  } else if (template === 'LICENSE_EXPIRING') {
    tagType = 'warning'
  }

  if (lines.length === 0) lines.push('通知详情暂不可用')
  return { title, lines, tagType }
}

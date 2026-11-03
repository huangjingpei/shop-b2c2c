/*
 * 文件：buyerReceipt.mjs
 * 用途：构造买家POST领取参数和不含敏感凭证的查单入口地址。
 * 维护注意：领取凭证只放请求体，分享卡片/海报不得包含；此处校验仅改善表单体验，授权与限流由后端执行。
 */
export function receiptRequest(orderSn, contact, queryToken) {
  const fields = [orderSn, contact, queryToken].map(value => typeof value === 'string' ? value.trim() : '')
  if (fields.some(value => !value || value.length > 64)) throw new Error('请输入完整的订单号、联系方式和买家领取凭证')
  return { orderSn: fields[0], contact: fields[1], queryToken: fields[2] }
}
export function receiptPath(orderSn) {
  return '/quick-query?sn=' + encodeURIComponent(orderSn)
}

export function receiptRouteQuery(query) {
  return typeof query.sn === 'string' ? { sn: query.sn } : {}
}

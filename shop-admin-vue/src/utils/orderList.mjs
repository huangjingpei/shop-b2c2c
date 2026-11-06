/*
 * 文件：orderList.mjs
 * 用途：T24 订单 keyset 分页的前端状态规则，统一商家端/平台端组装查询参数、追加结果与分页元数据。
 * 维护注意：cursor 对前端是不透明字符串，只能原样回传；切换筛选必须丢弃旧游标与旧列表。
 *           服务端返回 page.total/hasMore/nextCursor 是唯一权威，前端不得按数组长度推断是否还有下一页。
 */
export const ORDER_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'UNPAID', label: '待付款' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'CANCELLED', label: '已取消' }
]

export function orderStatusLabel(value) {
  return ORDER_STATUS_OPTIONS.find(option => option.value === value)?.label || value || '全部状态'
}

export function buildOrderQuery({ status = '', cursor = '', pageSize = 20 } = {}) {
  const params = { pageSize }
  if (status) params.status = status
  if (cursor) params.cursor = cursor
  return params
}

export function readOrderPage(payload) {
  const orders = Array.isArray(payload?.orders) ? payload.orders : []
  const page = payload?.page || {}
  return {
    orders,
    total: Number(page.total ?? orders.length),
    hasMore: Boolean(page.hasMore),
    nextCursor: page.nextCursor || ''
  }
}

export function mergeOrderPage(previous, payload, { reset = false } = {}) {
  const page = readOrderPage(payload)
  return {
    orders: reset ? page.orders : [...(previous?.orders || []), ...page.orders],
    total: page.total,
    hasMore: page.hasMore,
    nextCursor: page.nextCursor
  }
}

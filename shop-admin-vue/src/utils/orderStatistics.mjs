/*
 * 文件：orderStatistics.mjs
 * 用途：展示服务端状态统计和真实店铺信息，不在浏览器重新计算交易/退款金额。
 * 维护注意：金额字符串直接格式化保留大额分位精度；缺失值显示未知，不能回退旧总额或以0替代。上架数量仅按商品status=1，不代表店铺当前可交易；营业和费率必须来自实际资料。
 */
export function amountText(value) {
  if (typeof value !== 'string' || !/^\d+(\.\d{1,2})?$/.test(value)) return '—'
  const [whole, cents = ''] = value.split('.')
  return whole + '.' + cents.padEnd(2, '0')
}
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : '—'
export const STATISTICS_NOTICE = '按订单状态汇总，服务端默认统计近90天；退款状态订单仍计历史支付。实际退款、净额及已结算收入尚无资金账本，不能作为到账证明。'
export function sharingRecordLabel(status) {
  return ({ PENDING: '记录待处理', PROCESSING: '记录处理中', FAILED: '记录失败', FINISHED: '记录完成（待对账）' })[status] ?? '未识别记录'
}
export function statisticsView(statistics) {
  const s = statistics || {}
  return { totalOrders: count(s.totalOrders), paidOrders: count(s.paidOrders), pendingOrders: count(s.pendingOrders),
    refundedOrders: count(s.refundedOrders), unknownOrders: count(s.unknownOrders), amountAnomalyOrders: count(s.amountAnomalyOrders),
    paidTurnover: amountText(s.paidTurnover), pendingIntentAmount: amountText(s.pendingIntentAmount),
    estimatedCommission: amountText(s.estimatedCommission) }
}
export function listedProductCount(products) {
  return Array.isArray(products) ? products.filter(p => p?.status === 1).length : '—'
}
export function storeDisplay(store) {
  const s = store || {}
  return { name: s.storeName || '店铺资料未加载', code: s.storeCode ?? '—', rate: s.commissionRate ?? '—',
    status: ({ 0: '待审核', 1: '正常营业', 2: '已打烊', 3: '已封禁', 4: '已注销' })[s.status] ?? '状态未知' }
}

/** T31：支付账户审核状态，REJECTED 不能与待审核混淆。 */
export function paymentAccountView(account) {
  const status = account?.applyStatus
  if (status === 'PASSED') return { label: '官方审核已通过并开通', type: 'success' }
  if (status === 'REJECTED') return { label: '审核已驳回，请修改资料后重新提交', type: 'danger' }
  return { label: '审核中', type: 'warning' }
}

/*
 * 文件：orderStatistics.mjs
 * 用途：展示服务端状态统计和真实店铺信息，不在浏览器重新计算交易/退款金额。
 * 维护注意：金额字符串直接格式化保留大额分位精度；缺失值显示未知，不能回退旧总额或以0替代。上架数量仅按商品status=1，不代表店铺当前可交易；营业和费率必须来自实际资料。
 * D25：实际退款/退款净额/扣冲正佣金由服务端按 trade_refund 成功账本（success_at 切日）计算；支付按订单创建、退款按成功时点，跨期口径净额与扣冲正佣金可为负（实际退款恒非负），缺失账本时显示未知不得按0展示。
 */
export function amountText(value) {
  if (typeof value !== 'string' || !/^\d+(\.\d{1,2})?$/.test(value)) return '—'
  const [whole, cents = ''] = value.split('.')
  return whole + '.' + cents.padEnd(2, '0')
}
/** D25：净额为跨期口径允许为负；负数格式 -123.45 同样合法展示。 */
function signedAmountText(value) {
  if (typeof value !== 'string' || !/^-?\d+(\.\d{1,2})?$/.test(value)) return '—'
  const negative = value.startsWith('-')
  const [whole, cents = ''] = value.replace(/^-/, '').split('.')
  return (negative ? '-' : '') + whole + '.' + cents.padEnd(2, '0')
}
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : '—'
export const STATISTICS_NOTICE = '按订单状态汇总，服务端默认统计近90天；支付GMV含退款状态订单的历史支付，实际退款与净额按退款成功时点统计，跨期净额可为负；扣冲正佣金为快照估计扣减，非到账证明。'
export function sharingRecordLabel(status) {
  return ({ PENDING: '记录待处理', PROCESSING: '记录处理中', FAILED: '记录失败', FINISHED: '记录完成（待对账）' })[status] ?? '未识别记录'
}
export function statisticsView(statistics) {
  const s = statistics || {}
  return { totalOrders: count(s.totalOrders), paidOrders: count(s.paidOrders), pendingOrders: count(s.pendingOrders),
    refundedOrders: count(s.refundedOrders), unknownOrders: count(s.unknownOrders), amountAnomalyOrders: count(s.amountAnomalyOrders),
    paidTurnover: amountText(s.paidTurnover), pendingIntentAmount: amountText(s.pendingIntentAmount),
    estimatedCommission: amountText(s.estimatedCommission),
    actualRefundAmount: s.financialLedgerAvailable ? amountText(s.actualRefundAmount) : '—',
    netTurnover: s.financialLedgerAvailable ? signedAmountText(s.netTurnover) : '—',
    settledCommission: s.financialLedgerAvailable ? signedAmountText(s.settledCommission) : '—',
    financialLedgerAvailable: s.financialLedgerAvailable === true }
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

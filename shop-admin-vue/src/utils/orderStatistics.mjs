/*
 * 文件：orderStatistics.mjs
 * 用途：展示服务端状态统计和真实店铺信息，不在浏览器重新计算交易/退款金额。
 * 维护注意：金额字符串直接格式化保留大额分位精度；缺失值显示未知，不能回退旧总额或以0替代。上架数量仅按商品status=1，不代表店铺当前可交易；营业和费率必须来自实际资料。
 * D25：实际退款/退款净额/扣冲正佣金由服务端按 trade_refund 成功账本（success_at 切日）计算；支付按订单创建、退款按成功时点，跨期口径净额与扣冲正佣金可为负（实际退款恒非负），缺失账本时显示未知不得按0展示。
 * M2：渠道进件与结算账户必须区分“未提交/待核验/已核验/已驳回/已停用/未知”，无记录不得兜底为“审核中”。
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

/** T31/M2：渠道进件状态；无记录显示未提交，不能把空对象伪装成审核中。 */
export function paymentAccountView(account) {
  const status = account?.applyStatus
  if (!status) return { label: '未提交渠道进件', type: 'info' }
  if (status === 'PASSED') return { label: '官方审核已通过并开通', type: 'success' }
  if (status === 'REJECTED') return { label: '审核已驳回，请修改资料后重新提交', type: 'danger' }
  if (status === 'AUDITING' || status === 'PENDING') return { label: '渠道审核中', type: 'warning' }
  return { label: `未知进件状态：${status}`, type: 'info' }
}

/**
 * M3：按 M1 生效规则展示收款模式（环境应急关闭 > 数据库总闸 > 商家配置）。
 * gate 必须是服务端返回的 {enabled, emergencyDisabled}；为 null 表示总闸状态未获取，
 * 此时不得默认成商家直收。本函数只用于展示，下单仍以服务端解析与订单快照为准。
 */
export function collectionModeView(configuredMode, gate) {
  const configured = configuredMode === 'PLATFORM_COLLECT' || configuredMode === 'MERCHANT_DIRECT'
    ? configuredMode
    : ''
  if (!configured) {
    return { configured: '', effective: '', label: '模式未知', type: 'info', pending: false, unknown: true }
  }
  if (!gate) {
    return { configured, effective: '', label: '总闸状态未获取', type: 'info', pending: false, unknown: true }
  }
  const blocked = gate.emergencyDisabled === true || gate.enabled !== true
  const effective = blocked ? 'MERCHANT_DIRECT' : configured
  return {
    configured,
    effective,
    label: effective === 'PLATFORM_COLLECT' ? '平台代收' : '商家直收',
    type: effective === 'PLATFORM_COLLECT' ? 'warning' : 'success',
    pending: configured === 'PLATFORM_COLLECT' && effective === 'MERCHANT_DIRECT',
    unknown: false
  }
}

/** M2：阶段一结算账户核验状态；未知值原样提示，不猜测为已通过。 */
export function settlementAccountView(account) {
  const status = account?.status
  if (status === 'VERIFIED') return { label: '已核验', type: 'success' }
  if (status === 'PENDING') return { label: '待核验', type: 'warning' }
  if (status === 'REJECTED') return { label: '已驳回', type: 'danger' }
  if (status === 'DISABLED') return { label: '已停用', type: 'info' }
  return { label: status ? `未知状态：${status}` : '状态未知', type: 'info' }
}

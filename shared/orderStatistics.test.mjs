/*
 * 文件：orderStatistics.test.mjs
 * 用途：状态统计展示精度、未知值、零费率、真实店铺状态和商品上架口径测试。
 * 维护注意：不在前端计算金融净额；测试只验证展示规则，后端另测订单分类和租户隔离。缺失数据不能伪装零收入或已结算。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { amountText, statisticsView, listedProductCount, storeDisplay, paymentAccountView, settlementAccountView, collectionModeView, sharingRecordLabel } from '../shop-admin-vue/src/utils/orderStatistics.mjs'
test('金额字符串保持大额分位，不转浮点', () => {
  assert.equal(amountText('9007199254740993.03'), '9007199254740993.03')
  assert.equal(amountText('0'), '0.00'); assert.equal(amountText('0.1'), '0.10')
})
for (const value of [null, undefined, '', -1, 1.23, '1.001', '-1', 'NaN', '1e3']) {
  test(`非法或缺失金额显示未知：${String(value)}`, () => assert.equal(amountText(value), '—'))
}
test('缺失统一统计不回退旧总额或列表长度', () => {
  const view = statisticsView({ totalTurnover: 999, totalCommission: 50, orders: [{ orderStatus: 'UNPAID' }] })
  assert.equal(view.paidTurnover, '—'); assert.equal(view.estimatedCommission, '—'); assert.equal(view.paidOrders, '—')
  assert.equal(statisticsView().totalOrders, '—')
})
test('实际返回的零与合法计数正常展示', () => {
  const view = statisticsView({ totalOrders: 2, paidOrders: 0, paidTurnover: '0.00', estimatedCommission: '0.00' })
  assert.equal(view.paidOrders, 0); assert.equal(view.totalOrders, 2); assert.equal(view.paidTurnover, '0.00')
  assert.equal(statisticsView({ paidOrders: -1 }).paidOrders, '—')
})
test('仅统计上架商品，空列表与未加载不同', () => {
  assert.equal(listedProductCount([{ status: 0 }, { status: 1 }, { status: 2 }, null]), 1)
  assert.equal(listedProductCount([]), 0); assert.equal(listedProductCount(null), '—')
})
test('零佣金率和真实店铺状态不被演示值覆盖', () => {
  const display = storeDisplay({ storeName: '本店', storeCode: 'S0', commissionRate: 0, status: 2 })
  assert.equal(display.rate, 0); assert.equal(display.status, '已打烊'); assert.equal(display.code, 'S0')
  assert.equal(storeDisplay({ status: 3 }).status, '已封禁'); assert.equal(storeDisplay().status, '状态未知')
  // O6：状态 4 为已注销终态，不得落入“状态未知”。
  assert.equal(storeDisplay({ status: 4 }).status, '已注销')
  assert.notEqual(storeDisplay().code, 'STORE001')
})
test('M2：渠道进件区分通过、驳回、审核中与未提交，空对象不再伪装审核中', () => {
  assert.equal(paymentAccountView({ applyStatus: 'PASSED' }).type, 'success')
  assert.equal(paymentAccountView({ applyStatus: 'REJECTED' }).label.includes('驳回'), true)
  assert.equal(paymentAccountView({ applyStatus: 'REJECTED' }).type, 'danger')
  assert.equal(paymentAccountView({ applyStatus: 'AUDITING' }).label, '渠道审核中')
  assert.equal(paymentAccountView({ applyStatus: 'PENDING' }).label, '渠道审核中')
  assert.equal(paymentAccountView({}).label, '未提交渠道进件')
  assert.equal(paymentAccountView().label, '未提交渠道进件')
  assert.equal(paymentAccountView({ applyStatus: 'UNKNOWN_CHANNEL' }).type, 'info')
})
test('M3：收款模式按应急关闭/总闸/商家配置展示，不把未获取总闸默认为直收', () => {
  const gateOn = { enabled: true, emergencyDisabled: false }
  const gateOff = { enabled: false, emergencyDisabled: false }
  assert.equal(collectionModeView('PLATFORM_COLLECT', gateOn).label, '平台代收')
  assert.equal(collectionModeView('PLATFORM_COLLECT', gateOn).effective, 'PLATFORM_COLLECT')
  assert.equal(collectionModeView('PLATFORM_COLLECT', gateOff).label, '商家直收')
  assert.equal(collectionModeView('PLATFORM_COLLECT', gateOff).pending, true)
  assert.equal(collectionModeView('MERCHANT_DIRECT', gateOff).pending, false)
  assert.equal(collectionModeView('PLATFORM_COLLECT', { enabled: true, emergencyDisabled: true }).label, '商家直收')
  assert.equal(collectionModeView('PLATFORM_COLLECT', null).unknown, true)
  assert.equal(collectionModeView('', gateOn).unknown, true)
  assert.equal(collectionModeView('WEIRD', gateOn).label, '模式未知')
})
test('M2：结算账户核验状态不猜测已通过，未知值原样提示', () => {
  assert.equal(settlementAccountView({ status: 'VERIFIED' }).type, 'success')
  assert.equal(settlementAccountView({ status: 'PENDING' }).label, '待核验')
  assert.equal(settlementAccountView({ status: 'REJECTED' }).type, 'danger')
  assert.equal(settlementAccountView({ status: 'DISABLED' }).type, 'info')
  assert.equal(settlementAccountView({}).label, '状态未知')
  assert.equal(settlementAccountView({ status: 'WEIRD' }).label, '未知状态：WEIRD')
})
test('内部FINISHED记录不表述为资金到账', () => {
  assert.equal(sharingRecordLabel('FINISHED'), '记录完成（待对账）')
  assert.equal(sharingRecordLabel(null), '未识别记录'); assert.equal(sharingRecordLabel('FAILED'), '记录失败')
})
// ---------- D25 退款账本指标 ----------
test('D25：账本可用时透传实际退款/净额/扣冲正佣金，净额允许为负', () => {
  const view = statisticsView({ financialLedgerAvailable: true, actualRefundAmount: '12.30', netTurnover: '-5.10', settledCommission: '0.55' })
  assert.equal(view.actualRefundAmount, '12.30')
  assert.equal(view.netTurnover, '-5.10')
  assert.equal(view.settledCommission, '0.55')
  assert.equal(view.financialLedgerAvailable, true)
  assert.equal(statisticsView({ financialLedgerAvailable: true, netTurnover: '0' }).netTurnover, '0.00')
})
test('D25：账本不可用或字段非法时显示未知，不按0展示', () => {
  const off = statisticsView({ actualRefundAmount: '12.30', netTurnover: '5.00', settledCommission: '0.50' })
  assert.equal(off.financialLedgerAvailable, false)
  assert.equal(off.actualRefundAmount, '—'); assert.equal(off.netTurnover, '—'); assert.equal(off.settledCommission, '—')
  const bad = statisticsView({ financialLedgerAvailable: true, actualRefundAmount: '-1', netTurnover: null, settledCommission: '0.001' })
  assert.equal(bad.actualRefundAmount, '—'); assert.equal(bad.netTurnover, '—'); assert.equal(bad.settledCommission, '—')
})

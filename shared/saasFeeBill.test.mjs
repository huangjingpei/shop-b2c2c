/*
 * 文件：saasFeeBill.test.mjs
 * 用途：F10 SaaS 月费账单前端纯规则测试：状态文案、账期格式、列表查询、生成与线下缴费请求装配。
 * 维护注意：纯 Node 测试，不模拟服务端授权与金额计算；应收金额/账单状态只能由后端产生，
 * 后端另测幂等生成、免收判定、缴费复核与逾期扫描。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  BILL_STATUSES, PAYMENT_METHODS,
  generateBillPayload, monthLabel, paymentPayload, previousMonth, saasBillQuery, statusLabel, statusTag
} from '../shop-admin-vue/src/utils/saasFeeBill.mjs'

test('状态文案与标签覆盖全部状态', () => {
  assert.deepEqual(BILL_STATUSES, ['PENDING', 'PAID', 'WAIVED', 'OVERDUE'])
  assert.equal(statusLabel('PENDING'), '待缴费')
  assert.equal(statusLabel('PAID'), '已缴费')
  assert.equal(statusLabel('WAIVED'), '免收')
  assert.equal(statusLabel('OVERDUE'), '已逾期')
  assert.equal(statusLabel('UNKNOWN'), '未知状态')
  assert.equal(statusTag('OVERDUE'), 'danger')
  assert.equal(statusTag('NOPE'), 'info')
})

test('账期展示与默认上一月', () => {
  assert.equal(monthLabel('2026-10'), '2026年10月')
  assert.equal(monthLabel('2026-13'), '未知账期')
  assert.equal(monthLabel(''), '未知账期')
  assert.equal(previousMonth(new Date(2026, 0, 15)), '2025-12')
  assert.equal(previousMonth(new Date(2026, 10, 7)), '2026-10')
})

test('超管列表查询：空筛选归一为 null，合法分页原样保留', () => {
  assert.deepEqual(saasBillQuery({}),
    { merchantId: null, status: null, month: null, limit: 50, offset: 0 })
  assert.deepEqual(saasBillQuery({ merchantId: '7', status: 'OVERDUE', month: '2026-10', limit: 200, offset: 40 }),
    { merchantId: 7, status: 'OVERDUE', month: '2026-10', limit: 200, offset: 40 })
})

test('超管列表查询拒绝非法店铺/状态/账期/分页', () => {
  assert.throws(() => saasBillQuery({ merchantId: 0 }), '店铺编号')
  assert.throws(() => saasBillQuery({ merchantId: 'abc' }), '店铺编号')
  assert.throws(() => saasBillQuery({ status: 'UNKNOWN' }), '账单状态')
  assert.throws(() => saasBillQuery({ month: '2026/10' }), '账期月份')
  assert.throws(() => saasBillQuery({ limit: 0 }), '每页条数')
  assert.throws(() => saasBillQuery({ limit: 201 }), '每页条数')
  assert.throws(() => saasBillQuery({ offset: -1 }), '偏移量')
})

test('生成请求要求店铺与 yyyy-MM 账期', () => {
  assert.deepEqual(generateBillPayload({ merchantId: 7, month: '2026-10' }), { merchantId: 7, month: '2026-10' })
  assert.throws(() => generateBillPayload({ month: '2026-10' }), /请选择店铺/)
  assert.throws(() => generateBillPayload({ merchantId: 7, month: '202610' }), '账期月份')
})

test('线下缴费请求：凭证必填、方式仅 OFFLINE、备注限长、复核人可选', () => {
  assert.deepEqual(paymentPayload({ voucherNo: ' V-001 ', remark: ' 银行转账 ' }),
    { paymentMethod: 'OFFLINE', voucherNo: 'V-001', remark: '银行转账', confirmedBy: null })
  assert.deepEqual(paymentPayload({ voucherNo: 'V-002', confirmedBy: '3' }),
    { paymentMethod: 'OFFLINE', voucherNo: 'V-002', remark: '', confirmedBy: 3 })
  assert.deepEqual(PAYMENT_METHODS, ['OFFLINE'])
  assert.throws(() => paymentPayload({ voucherNo: '   ' }), '凭证号')
  assert.throws(() => paymentPayload({ voucherNo: 'V'.repeat(65) }), '凭证号')
  assert.throws(() => paymentPayload({ voucherNo: 'V-1', paymentMethod: 'CASH' }), '线下转账')
  assert.throws(() => paymentPayload({ voucherNo: 'V-1', remark: 'x'.repeat(256) }), '备注')
  assert.throws(() => paymentPayload({ voucherNo: 'V-1', confirmedBy: 0 }), '复核人')
})

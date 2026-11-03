/*
 * 文件：serviceFee.test.mjs
 * 用途：服务费五种编码展示、编辑回填及提交字段的规则回归。
 * 维护注意：纯Node测试，不调用API；不把试用与豁免、逾期与欠费互换。日期只取LocalDateTime原日期，不按浏览器时区偏移；空日期不代表清除数据库值。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { FEE_STATUS_OPTIONS, feeStatusView, serviceFeeForm, serviceFeePayload } from '../shop-admin-vue/src/utils/serviceFee.mjs'

test('可选编码完整且不将试用、豁免、逾期和欠费合并', () => {
  assert.deepEqual(FEE_STATUS_OPTIONS.map(x => x.value), ['NORMAL', 'TRIAL', 'OVERDUE', 'EXEMPTED', 'ARREARS'])
  assert.equal(feeStatusView('TRIAL').label, '免费期')
  assert.equal(feeStatusView('OVERDUE').type, 'danger')
  assert.notEqual(feeStatusView('TRIAL').label, feeStatusView('EXEMPTED').label)
  assert.notEqual(feeStatusView('OVERDUE').label, feeStatusView('ARREARS').label)
})
for (const code of ['NORMAL', 'TRIAL', 'OVERDUE', 'EXEMPTED', 'ARREARS']) {
  test(`合法状态回填和保存保持原编码：${code}`, () => {
    const form = serviceFeeForm({ feeStatus: code, monthlyFee: 0, feeFreeUntil: '2028-02-29T00:00:00' })
    assert.deepEqual(serviceFeePayload(form), { feeStatus: code, monthlyFee: 0, feeFreeUntil: '2028-02-29' })
  })
}
for (const code of [undefined, null, '', 'UNKNOWN', 'normal', ' TRIAL', 'ARREARS ']) {
  test(`未知状态不回退正常，需用户明确选择：${String(code)}`, () => {
    assert.equal(feeStatusView(code).label, '服务费状态未知')
    const form = serviceFeeForm({ feeStatus: code })
    assert.equal(form.feeStatus, '')
    assert.throws(() => serviceFeePayload(form), /请选择有效的服务费状态/)
  })
}
test('允许用户明确将未知状态改成既有合法编码', () => {
  const form = serviceFeeForm({ feeStatus: 'UNKNOWN', monthlyFee: 10 })
  form.feeStatus = 'TRIAL'
  assert.equal(serviceFeePayload(form).feeStatus, 'TRIAL')
})
test('空截止日发送null表示保留；请求不携带商户身份与额外字段', () => {
  const form = serviceFeeForm({ feeStatus: 'NORMAL', feeFreeUntil: null })
  assert.deepEqual(serviceFeePayload({ ...form, merchantId: 999, status: 3 }), {
    monthlyFee: 0, feeStatus: 'NORMAL', feeFreeUntil: null
  })
})
test('已有日期按原日历日期回填，不做时区转换', () => {
  assert.equal(serviceFeeForm({ feeFreeUntil: '2026-11-04' }).feeFreeUntil, '2026-11-04')
  assert.equal(serviceFeeForm({ feeFreeUntil: '2026-11-04T00:00:00+08:00' }).feeFreeUntil, '2026-11-04')
})

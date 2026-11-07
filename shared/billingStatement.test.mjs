/*
 * 文件：billingStatement.test.mjs
 * 用途：F09 商户账单前端纯规则测试：查询参数校验（白名单/日期/跨度/页码）、导出文件名与重算请求装配。
 * 维护注意：纯Node测试，不模拟服务端授权与聚合；跨度上限（日92天/月24个月）、from≤to、size 1..100
 * 口径须与后端 BillingStatementService 保持一致；后端另测事务、upsert 幂等与角色校验。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DAILY_SPAN_MAX, MONTHLY_SPAN_MAX,
  billingCsvFilename, billingQuery, billingStatusLabel, billingStatusTag, periodTypeLabel, regeneratePayload
} from '../shop-admin-vue/src/utils/billingStatement.mjs'

test('合法日/月账单查询参数原样装配', () => {
  assert.deepEqual(billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31' }),
    { periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31', page: 1, size: 20 })
  const monthly = billingQuery({ periodType: 'MONTHLY', from: '2026-01-01', to: '2026-06-01', page: 3, size: 50 })
  assert.equal(monthly.page, 3)
  assert.equal(monthly.size, 50)
})

test('账期类型仅允许 DAILY/MONTHLY', () => {
  for (const bad of ['WEEKLY', 'daily', '', null, undefined])
    assert.throws(() => billingQuery({ periodType: bad, from: '2026-10-01', to: '2026-10-31' }), '账期类型')
})

test('起止日期必填、格式正确且 from 不晚于 to', () => {
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '', to: '2026-10-31' }), '起止日期')
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: null }), '起止日期')
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: 'not-a-date', to: '2026-10-31' }), '格式')
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-10-31', to: '2026-10-01' }), '不能晚于')
})

test('日账单跨度最长 92 天，边界通过', () => {
  assert.equal(DAILY_SPAN_MAX, 92)
  assert.doesNotThrow(() => billingQuery({ periodType: 'DAILY', from: '2026-01-01', to: '2026-04-02' }))
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-01-01', to: '2026-04-03' }), '92 天')
})

test('月账单跨度最长 24 个月，边界通过', () => {
  assert.equal(MONTHLY_SPAN_MAX, 24)
  assert.doesNotThrow(() => billingQuery({ periodType: 'MONTHLY', from: '2026-01-15', to: '2027-12-01' }))
  assert.throws(() => billingQuery({ periodType: 'MONTHLY', from: '2026-01-01', to: '2028-01-01' }), '24 个月')
})

test('页码与每页条数校验：page≥1、size 1..100', () => {
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31', page: 0 }), '页码')
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31', size: 0 }), '每页')
  assert.throws(() => billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31', size: 101 }), '每页')
  assert.doesNotThrow(() => billingQuery({ periodType: 'DAILY', from: '2026-10-01', to: '2026-10-31', page: 1, size: 100 }))
})

test('导出文件名：无店铺时 scope 为 all，类型小写', () => {
  assert.equal(billingCsvFilename(null, 'DAILY', '2026-10-01', '2026-10-31'),
    'billing-all-daily-2026-10-01-2026-10-31.csv')
  assert.equal(billingCsvFilename(7, 'MONTHLY', '2026-01-01', '2026-12-01'),
    'billing-7-monthly-2026-01-01-2026-12-01.csv')
  assert.equal(billingCsvFilename('', 'DAILY', '2026-10-01', '2026-10-02'),
    'billing-all-daily-2026-10-01-2026-10-02.csv')
  assert.throws(() => billingCsvFilename(7, 'WEEKLY', '2026-10-01', '2026-10-02'), '账期类型')
})

test('重算请求：店铺编号转数字，字符串日期原样回传', () => {
  assert.deepEqual(regeneratePayload({ shopId: '7', periodDate: '2026-10-01', periodType: 'MONTHLY' }),
    { shopId: 7, periodDate: '2026-10-01', periodType: 'MONTHLY' })
  assert.deepEqual(regeneratePayload({ shopId: 3, periodDate: '2026-11-07', periodType: 'DAILY' }),
    { shopId: 3, periodDate: '2026-11-07', periodType: 'DAILY' })
})

test('重算请求：店铺编号必须为正整数', () => {
  for (const bad of ['', '0', '-1', '1.5', 'abc', null, undefined])
    assert.throws(() => regeneratePayload({ shopId: bad, periodDate: '2026-10-01', periodType: 'DAILY' }), '店铺编号')
})

test('重算请求：类型白名单与日期必填', () => {
  assert.throws(() => regeneratePayload({ shopId: 1, periodDate: '2026-10-01', periodType: 'YEARLY' }), '账期类型')
  assert.throws(() => regeneratePayload({ shopId: 1, periodDate: '', periodType: 'DAILY' }), '日期必填')
  assert.throws(() => regeneratePayload({ shopId: 1, periodDate: 'bad-date', periodType: 'DAILY' }), '日期必填')
})

test('状态与类型文案映射及未知兜底', () => {
  assert.equal(periodTypeLabel('DAILY'), '日账单')
  assert.equal(periodTypeLabel('MONTHLY'), '月账单')
  assert.equal(periodTypeLabel('X'), '未知类型')
  assert.equal(billingStatusLabel('PENDING'), '核算中')
  assert.equal(billingStatusLabel('SETTLED'), '已结算出账')
  assert.equal(billingStatusLabel('DISPUTED'), '有争议')
  assert.equal(billingStatusLabel(undefined), '未知状态')
  assert.equal(billingStatusTag('PENDING'), 'info')
  assert.equal(billingStatusTag('SETTLED'), 'success')
  assert.equal(billingStatusTag('DISPUTED'), 'danger')
  assert.equal(billingStatusTag('OTHER'), 'info')
})

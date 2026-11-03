/*
 * 文件：cardAssets.test.mjs
 * 用途：卡密导入SKU选择、批内边界、批次游标参数及作废权限/状态规则测试。
 * 维护注意：只使用虚构文本，不使用真实卡密；服务端另测历史去重、事务、并发和预占拒绝。本地展示判断不能作为资产授权。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { cardImportPayload, cardStatusLabel, canVoidCard, cardBatchQuery } from '../shop-admin-vue/src/utils/cardAssets.mjs'
const form = () => ({ productId: 9, skuId: 12, rawText: ' Code-A \r\n\nCode-b' })
test('批次查询只包含有界游标参数，可清空SKU筛选', () => {
  assert.deepEqual(cardBatchQuery(' 12 ', 30), { skuId: 12, beforeId: 30, limit: 20 })
  assert.deepEqual(cardBatchQuery(' ', null), { skuId: undefined, beforeId: undefined, limit: 20 })
})
for (const value of [0, -1, 1.5, '1e3', true, 'not-an-id', Number.MAX_SAFE_INTEGER + 1]) {
  test(`批次查询拒绝无效筛选或游标：${String(value)}`, () => {
    assert.throws(() => cardBatchQuery(value, null)); assert.throws(() => cardBatchQuery(null, value))
  })
}
test('提交实际选定的商品与SKU，保留卡密大小写', () => {
  assert.deepEqual(cardImportPayload(form()), { productId: 9, skuId: 12, cardContents: ['Code-A', 'Code-b'] })
})
test('去除首尾空白后批内重复必须拒绝', () => {
  assert.throws(() => cardImportPayload({ ...form(), rawText: 'same\n same ' }))
  assert.equal(cardImportPayload({ ...form(), rawText: 'same\nSAME' }).cardContents.length, 2)
})
test('纯控制字符清理后为空时不能导入', () => {
  assert.throws(() => cardImportPayload({ ...form(), rawText: String.fromCharCode(0) }))
})
for (const rawText of ['', ' ', Array.from({ length: 1001 }, (_, i) => `fixture-${i}`).join('\n'), 'x'.repeat(4097)]) {
  test(`拒绝空或超限批次，输入长度${rawText.length}`, () => assert.throws(() => cardImportPayload({ ...form(), rawText })))
}
test('未选择商品/SKU或非整数ID不能导入', () => {
  for (const patch of [{ productId: null }, { skuId: '' }, { productId: 1.5 }, { skuId: -1 }]) assert.throws(() => cardImportPayload({ ...form(), ...patch }))
})
test('只有店长和超管可以操作未售且未绑定的卡', () => {
  assert.equal(canVoidCard('MERCHANT_ADMIN', { status: 0 }), true)
  assert.equal(canVoidCard('SUPERADMIN', { status: 0, orderSn: null }), true)
  for (const status of [1, 2, 9]) assert.equal(canVoidCard('MERCHANT_ADMIN', { status }), false)
  assert.equal(canVoidCard('MERCHANT_STAFF', { status: 0 }), false)
  assert.equal(canVoidCard('MERCHANT_ADMIN', { status: 0, orderSn: 'bound' }), false)
  assert.equal(cardStatusLabel(2), '已作废'); assert.equal(cardStatusLabel(1), '已售出'); assert.equal(cardStatusLabel(9), '未知状态')
})

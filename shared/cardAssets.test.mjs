/*
 * 文件：cardAssets.test.mjs
 * 用途：卡密导入SKU选择、批内边界、批次/库存游标参数、导出确认载荷、作废权限/状态及一致性核对行状态规则测试。
 * 维护注意：只使用虚构文本，不使用真实卡密；服务端另测历史去重、事务、并发、限量查看与导出审计。本地展示判断不能作为资产授权，也不能替代接口层拒绝员工。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { cardImportPayload, cardStatusLabel, canVoidCard, cardBatchQuery, cardListQuery, cardExportPayload, consistencyIssue, canReissueCard, reissuePayload } from '../shop-admin-vue/src/utils/cardAssets.mjs'
const form = () => ({ productId: 9, skuId: 12, rawText: ' Code-A \r\n\nCode-b' })
test('批次查询只包含有界游标参数，可清空SKU筛选', () => {
  assert.deepEqual(cardBatchQuery(' 12 ', 30), { skuId: '12', beforeId: '30', limit: 20 })
  assert.deepEqual(cardBatchQuery(' ', null), { skuId: undefined, beforeId: undefined, limit: 20 })
})
for (const value of [0, -1, 1.5, '1e3', true, 'not-an-id', Number.MAX_SAFE_INTEGER + 1]) {
  test(`批次查询拒绝无效筛选或游标：${String(value)}`, () => {
    assert.throws(() => cardBatchQuery(value, null)); assert.throws(() => cardBatchQuery(null, value))
  })
}
test('库存查询携带仅未售筛选与游标，空SKU不发送筛选', () => {
  assert.deepEqual(cardListQuery('12', true, 30), { skuId: '12', onlyAvailable: true, beforeId: '30', limit: 20 })
  assert.deepEqual(cardListQuery('', false, null), { skuId: undefined, onlyAvailable: false, beforeId: undefined, limit: 20 })
})
for (const value of [0, -1, 1.5, '1e3', true, 'not-an-id']) {
  test(`库存查询拒绝无效SKU或游标：${String(value)}`, () => {
    assert.throws(() => cardListQuery(value, false, null)); assert.throws(() => cardListQuery('', false, value))
  })
}
test('导出载荷固定二次确认、条数有上限且必须选择SKU', () => {
  assert.deepEqual(cardExportPayload('12'), { skuId: '12', confirm: true, onlyAvailable: true, limit: 500 })
  assert.deepEqual(cardExportPayload(12, false), { skuId: '12', confirm: true, onlyAvailable: false, limit: 500 })
  for (const value of ['', null, 0, -1, 1.5, '1e3', true, 'not-an-id']) assert.throws(() => cardExportPayload(value))
})
test('T37 大于2^53的字符串SKU与游标原样提交，已失真的number拒绝', () => {
  assert.equal(cardExportPayload('9007199254740993').skuId, '9007199254740993')
  assert.equal(cardBatchQuery('9007199254740995', '9007199254740997').beforeId, '9007199254740997')
  assert.throws(() => cardExportPayload(Number.MAX_SAFE_INTEGER + 1))
})
test('提交实际选定的商品与SKU，保留卡密大小写', () => {
  assert.deepEqual(cardImportPayload(form()), { productId: '9', skuId: '12', cardContents: ['Code-A', 'Code-b'] })
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
test('F12 一致性核对行状态：异常指纹优先于漂移，严格一致才算ok', () => {
  assert.equal(consistencyIssue({ consistent: true, fingerprintUnavailable: 0 }), 'ok')
  assert.equal(consistencyIssue({ consistent: false, fingerprintUnavailable: 0 }), 'drift')
  assert.equal(consistencyIssue({ consistent: true, fingerprintUnavailable: '2' }), 'unavailable')
  assert.equal(consistencyIssue({ consistent: false, fingerprintUnavailable: 1 }), 'unavailable')
  assert.equal(consistencyIssue({}), 'drift')
  assert.equal(consistencyIssue(null), 'unknown')
  assert.equal(consistencyIssue('row'), 'unknown')
})
test('F12 补发展示：仅店长/超管且卡已售并绑定订单', () => {
  assert.equal(canReissueCard('MERCHANT_ADMIN', { status: 1, orderSn: 'O1' }), true)
  assert.equal(canReissueCard('SUPERADMIN', { status: 1, orderSn: 'O1' }), true)
  assert.equal(canReissueCard('MERCHANT_STAFF', { status: 1, orderSn: 'O1' }), false)
  assert.equal(canReissueCard('MERCHANT_ADMIN', { status: 0, orderSn: null }), false)
  assert.equal(canReissueCard('MERCHANT_ADMIN', { status: 1, orderSn: null }), false)
  assert.equal(canReissueCard('MERCHANT_ADMIN', { status: 1, orderSn: '' }), false)
  assert.equal(canReissueCard('MERCHANT_ADMIN', { status: 2, orderSn: 'O1' }), false)
  assert.equal(canReissueCard('MERCHANT_ADMIN', null), false)
})
test('F12 补发载荷：原因trim必填且最多255字符', () => {
  assert.deepEqual(reissuePayload(' 买家未收到 '), { reason: '买家未收到' })
  assert.equal(reissuePayload('x'.repeat(255)).reason.length, 255)
  assert.throws(() => reissuePayload('  '), /请填写补发原因/)
  assert.throws(() => reissuePayload(null), /请填写补发原因/)
  assert.throws(() => reissuePayload('x'.repeat(256)), /最多255字符/)
})

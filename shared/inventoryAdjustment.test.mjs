/*
 * 文件：inventoryAdjustment.test.mjs
 * 用途：库存调整权限展示、字段白名单、整数边界及幂等重试参数测试。
 * 维护注意：纯规则测试不代表后端授权；预占容量、并发和数据库事务另由后端隔离测试验证，不能用前端预期数覆盖实时数量。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { canAdjustInventory, inventoryPayload } from '../shop-admin-vue/src/utils/inventoryAdjustment.mjs'
import { createIdempotentSubmission } from '../shop-admin-vue/src/utils/idempotency.mjs'
const form = () => ({ productId: 1, skuId: 2, delta: 3, expectedStock: 10, reason: '  补货  ', merchantId: 99, actorId: 99 })
test('只提交库存业务字段，不接受伪造操作人', () => {
  assert.deepEqual(inventoryPayload(form()), { productId: '1', skuId: '2', delta: 3, expectedStock: 10, reason: '补货' })
})
test('店长和超管可调实物/服务，员工及卡密不显示写入口', () => {
  for (const role of ['SUPERADMIN', 'MERCHANT_ADMIN']) for (const type of [1, 3]) assert.equal(canAdjustInventory(role, type), true)
  for (const role of ['MERCHANT_STAFF', '', 'UNKNOWN']) assert.equal(canAdjustInventory(role, 1), false)
  assert.equal(canAdjustInventory('MERCHANT_ADMIN', 2), false)
})
for (const delta of [0, 0.5, 1000001, -1000001, NaN, Infinity, '']) test(`拒绝非法调整量：${String(delta)}`, () => {
  assert.throws(() => inventoryPayload({ ...form(), delta }))
})
test('合法扣减和整数边界，不能扣为负数或溢出', () => {
  assert.equal(inventoryPayload({ ...form(), delta: -10 }).delta, -10)
  assert.equal(inventoryPayload({ ...form(), delta: 1000000 }).delta, 1000000)
  assert.throws(() => inventoryPayload({ ...form(), delta: -11 }))
  assert.throws(() => inventoryPayload({ ...form(), expectedStock: 2147483647, delta: 1 }))
})
test('必填原因、有效当前数量和SKU标识不可省略', () => {
  for (const patch of [{ reason: ' ' }, { reason: 'x'.repeat(256) }, { expectedStock: null }, { expectedStock: '' }, { expectedStock: -1 }, { skuId: 0 }, { productId: 0 }]) {
    assert.throws(() => inventoryPayload({ ...form(), ...patch }))
  }
})
test('T37 大于2^53的字符串编号原样提交，已失真的number拒绝', () => {
  const payload = inventoryPayload({ ...form(), productId: '9007199254740993', skuId: '9007199254740995' })
  assert.equal(payload.productId, '9007199254740993')
  assert.equal(payload.skuId, '9007199254740995')
  assert.throws(() => inventoryPayload({ ...form(), productId: Number.MAX_SAFE_INTEGER + 1 }))
})
test('未确认成功的重试复用原键和快照，确认后下一次使用新键', () => {
  let count = 0; const submit = createIdempotentSubmission(() => `operation-${++count}`)
  const first = submit.prepare(inventoryPayload(form()))
  assert.deepEqual(submit.prepare(inventoryPayload(form())), first)
  submit.complete()
  assert.notEqual(submit.prepare(inventoryPayload(form())).headers['Idempotency-Key'], first.headers['Idempotency-Key'])
})

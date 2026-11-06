/*
 * 文件：idempotency.test.mjs
 * 用途：验证两端页面提交幂等键在重试、参数变化和确认成功时的生命周期。
 * 维护注意：使用注入的确定性工厂，不依赖网络或随机碰撞；不要把失败重试改成每次生成新键。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import * as admin from '../shop-admin-vue/src/utils/idempotency.mjs'
import * as storefront from '../shop-storefront-vue/src/utils/idempotency.mjs'
for (const [name, module] of [['admin', admin], ['storefront', storefront]]) {
  test(`${name}: 未确认成功的重复提交复用原键`, () => {
    let count = 0
    const submit = module.createIdempotentSubmission(() => `key-${++count}`)
    assert.deepEqual(submit.prepare({ sku: 1 }), submit.prepare({ sku: 1 }))
    assert.equal(count, 1)
  })
  test(`${name}: 参数变化使用新键`, () => {
    let count = 0
    const submit = module.createIdempotentSubmission(() => `key-${++count}`)
    assert.notDeepEqual(submit.prepare({ quantity: 1 }).headers, submit.prepare({ quantity: 2 }).headers)
    assert.equal(count, 2)
  })
  test(`${name}: 成功后相同参数可进行新的独立购买`, () => {
    let count = 0
    const submit = module.createIdempotentSubmission(() => `key-${++count}`)
    const first = submit.prepare({ sku: 1 }); submit.complete()
    assert.notDeepEqual(first, submit.prepare({ sku: 1 }))
  })
  test(`${name}: 请求体快照不随原表单编辑变化`, () => {
    let count = 0
    const submit = module.createIdempotentSubmission(() => `key-${++count}`)
    const form = { quantity: 1, nested: { contact: 'buyer' } }
    const first = submit.prepare(form)
    form.quantity = 2; form.nested.contact = 'changed'
    assert.deepEqual(first.body, { quantity: 1, nested: { contact: 'buyer' } })
    assert.notDeepEqual(first.headers, submit.prepare(form).headers)
  })
  test(`${name}: 返回的请求头不能修改内部保存的键`, () => {
    const submit = module.createIdempotentSubmission(() => 'private-key')
    submit.prepare({ sku: 1 }).headers['Idempotency-Key'] = 'tampered'
    assert.equal(submit.prepare({ sku: 1 }).headers['Idempotency-Key'], 'private-key')
  })
  test(`${name}: 优先使用 crypto.randomUUID`, () => {
    const key = module.createIdempotencyKey({
      randomUUID: () => 'fixed-uuid',
      getRandomValues: () => { throw new Error('不应走降级分支') }
    })
    assert.equal(key, 'fixed-uuid')
  })
  test(`${name}: 非安全上下文无randomUUID时用getRandomValues生成UUIDv4`, () => {
    const key = module.createIdempotencyKey({
      getRandomValues(bytes) {
        for (let i = 0; i < bytes.length; i++) bytes[i] = i
        return bytes
      }
    })
    assert.equal(key, '00010203-0405-4607-8809-0a0b0c0d0e0f')
  })
  test(`${name}: 没有安全随机源时明确报错，不退化为Math.random`, () => {
    assert.throws(() => module.createIdempotencyKey({}), /安全随机源/)
  })
}

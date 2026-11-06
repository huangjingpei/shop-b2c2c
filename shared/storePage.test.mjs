/*
 * 文件：storePage.test.mjs
 * 用途：公开店铺页面独立读取、空店、异常与商户隔离的规则测试。
 * 维护注意：注入请求函数，不访问网络；业务失败即使HTTP成功也不能使用data，Vue路由竞争和模板渲染另由浏览器人工验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { loadStorePage } from '../shop-storefront-vue/src/utils/storePage.mjs'

const shop = { id: 7, storeName: '空店', notice: '真实公告', contactPhone: null }
const ok = data => ({ data: { code: 200, data } })

test('空店独立展示真实资料，商品查询限定本店', async () => {
  const calls = []
  const state = await loadStorePage('7', async (...args) => {
    calls.push(args)
    return calls.length === 1 ? ok(shop) : ok([])
  })
  assert.deepEqual(calls, [['/api/v1/public/stores/7'], ['/api/v1/public/products', { params: { merchantId: '7' } }]])
  assert.deepEqual(state, { store: shop, products: [], error: '', productsError: '' })
})
test('T30 店内搜索把 trim 后的关键词作为 q 传参，仍限定本店', async () => {
  const calls = []
  // fetcher 必须让店铺校验通过（返回本店资料），商品请求才会发出。
  await loadStorePage('7', async (...args) => { calls.push(args); return args[0].includes('/stores/') ? ok(shop) : ok([]) }, '  耳机  ')
  assert.deepEqual(calls[1], ['/api/v1/public/products', { params: { merchantId: '7', q: '耳机' } }])
})
test('T30 空白关键词不追加 q', async () => {
  const calls = []
  await loadStorePage('7', async (...args) => { calls.push(args); return args[0].includes('/stores/') ? ok(shop) : ok([]) }, '   ')
  assert.deepEqual(calls[1], ['/api/v1/public/products', { params: { merchantId: '7' } }])
})
test('正常商品保留，联系方式缺失不伪造', async () => {
  const product = { id: 3, merchantId: 7 }
  const state = await loadStorePage('7', async url => ok(url.includes('/stores/') ? shop : [product]))
  assert.deepEqual(state.products, [product]); assert.equal(state.store.contactPhone, null)
})
for (const id of ['', '0', '-1', '01', '1.2', '1e3', '7/other', '9223372036854775808', null, 7]) {
  test(`非法店铺编号不发送请求：${String(id)}`, async () => {
    const state = await loadStorePage(id, () => assert.fail('不得请求'))
    assert.equal(state.error, '店铺编号不正确'); assert.equal(state.store, null)
  })
}
test('店铺不可用不继续查询商品，也不采用失败响应中的资料', async () => {
  let calls = 0
  const state = await loadStorePage('7', async () => {
    calls++; return { data: { code: 404, data: shop } }
  })
  assert.equal(calls, 1); assert.equal(state.store, null); assert.equal(state.error, '店铺不存在或暂未营业')
})
for (const response of [null, { data: { code: 500, data: shop } }, ok(null), ok({ id: 8 })]) {
  test(`资料响应异常不得显示店铺：${JSON.stringify(response)}`, async () => {
    const state = await loadStorePage('7', async () => response)
    assert.equal(state.store, null); assert.ok(state.error)
  })
}
test('网络失败不泄露异常内容', async () => {
  const state = await loadStorePage('7', async () => { throw new Error('private-token') })
  assert.equal(state.error, '店铺资料加载失败，请稍后重试')
})
for (const response of [null, ok(null), ok([null]), ok([{ merchantId: 8 }]), { data: { code: 500, data: [] } }]) {
  test(`商品异常保留资料但不能当空店：${JSON.stringify(response)}`, async () => {
    const state = await loadStorePage('7', async url => url.includes('/stores/') ? ok(shop) : response)
    assert.equal(state.store, shop); assert.equal(state.error, '')
    assert.ok(state.productsError); assert.deepEqual(state.products, [])
  })
}
test('商品网络失败保留店铺且允许页面重试', async () => {
  const state = await loadStorePage('7', async url => {
    if (url.includes('/stores/')) return ok(shop)
    throw new Error('network')
  })
  assert.equal(state.store, shop); assert.ok(state.productsError)
})

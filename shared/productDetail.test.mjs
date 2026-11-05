/*
 * 文件：productDetail.test.mjs
 * 用途：商品详情加载状态机回归测试，覆盖失败重置、成功后选中首个SKU、非法响应拒绝，以及setup阶段绑定先于watch声明的TDZ约束。
 * 维护注意：用例只使用内存ref替身与本地源码文本，不访问网络、不挂载Vue组件；真实路由切换与浏览器渲染仍需人工验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { loadProductDetail, applyProductDetail, resetProductDetailState } from '../shop-storefront-vue/src/utils/productDetail.mjs'

const detailSource = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '..', 'shop-storefront-vue', 'src', 'views', 'ProductDetail.vue'),
  'utf8'
)

const ref = value => ({ value })
const createState = () => ({
  loading: ref(false),
  product: ref({ id: 9, title: '旧商品' }),
  skus: ref([{ id: 9 }]),
  store: ref({ id: 1 }),
  currentSku: ref({ id: 9 }),
  successResult: ref({ orderNo: 'OLD' }),
  peerPayResult: ref({ token: 'OLD' })
})

test('加载失败时清空旧商品与旧结果，并复位loading', async () => {
  const state = createState()
  const result = await loadProductDetail('3', async () => { throw new Error('network down') }, state)
  assert.equal(result.ok, false)
  assert.equal(state.loading.value, false)
  assert.equal(state.product.value, null)
  assert.deepEqual(state.skus.value, [])
  assert.equal(state.currentSku.value, null)
  assert.equal(state.successResult.value, null)
  assert.equal(state.peerPayResult.value, null)
})

test('加载成功时写入商品、SKU并默认选中首个SKU', async () => {
  const state = createState()
  const product = { id: 3, title: '新品' }
  const skus = [{ id: 31, price: 10 }, { id: 32, price: 20 }]
  const store = { id: 7 }
  const result = await loadProductDetail('3', async () => ({ data: { data: { product, skus, store } } }), state)
  assert.equal(result.ok, true)
  assert.equal(state.loading.value, false)
  assert.deepEqual(state.product.value, product)
  assert.deepEqual(state.skus.value, skus)
  assert.deepEqual(state.currentSku.value, skus[0])
  assert.deepEqual(state.store.value, store)
  assert.equal(state.successResult.value, null)
})

test('无店铺或无SKU时保持空值，不伪造数据', async () => {
  const state = createState()
  const product = { id: 3, title: '单规格' }
  await loadProductDetail('3', async () => ({ data: { data: { product } } }), state)
  assert.deepEqual(state.skus.value, [])
  assert.equal(state.currentSku.value, null)
  assert.equal(state.store.value, null)
})

test('缺少有效商品时按失败处理并复位loading', async () => {
  const state = createState()
  for (const payload of [null, {}, { product: {} }, { product: { title: '无编号' } }]) {
    const result = await loadProductDetail('3', async () => ({ data: { data: payload } }), state)
    assert.equal(result.ok, false)
    assert.equal(state.loading.value, false)
    assert.equal(state.product.value, null)
  }
})

test('空编号不请求且不复位当前状态', async () => {
  const state = createState()
  let called = 0
  const result = await loadProductDetail('', async () => { called++; return null }, state)
  assert.equal(result.ok, false)
  assert.equal(called, 0)
  assert.deepEqual(state.product.value, { id: 9, title: '旧商品' })
})

test('reset与apply保持职责边界', () => {
  const state = createState()
  resetProductDetailState(state)
  assert.equal(state.product.value, null)
  assert.deepEqual(state.skus.value, [])
  applyProductDetail(state, { product: { id: 1 }, skus: [{ id: 11 }] })
  assert.deepEqual(state.product.value, { id: 1 })
  assert.deepEqual(state.currentSku.value, { id: 11 })
})

test('代付等视图绑定声明必须早于watch immediate执行', () => {
  const watchIndex = detailSource.indexOf('watch(() => route.params.id')
  assert.ok(watchIndex >= 0, '未找到路由watch')
  for (const name of ['peerPayModalOpen', 'generatingPeerPay', 'peerPayResult', 'peerMessage', 'quickMessages', 'isWeChat', 'showWechatGuide']) {
    const declaration = detailSource.indexOf(`const ${name} =`)
    assert.ok(declaration >= 0 && declaration < watchIndex, `${name} 必须在 watch 之前声明`)
  }
  assert.equal(detailSource.split('const peerPayResult =').length - 1, 1, 'peerPayResult 不得重复声明')
})

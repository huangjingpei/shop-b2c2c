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
import { loadProductDetail, applyProductDetail, resetProductDetailState, parseDetailImages, isSkuSoldOut, isProductSoldOut } from '../shop-storefront-vue/src/utils/productDetail.mjs'

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

test('T28 旧请求迟到不得覆盖新商品或复位新请求loading', async () => {
  const state = createState()
  let releaseOld
  const gate = new Promise(resolve => { releaseOld = resolve })
  let current = 'old'
  const oldCall = loadProductDetail(
    '1',
    async () => { await gate; return { data: { data: { product: { id: 1, title: '旧商品' } } } } },
    state,
    () => current === 'old'
  )
  current = 'new'
  const newCall = loadProductDetail(
    '2',
    async () => ({ data: { data: { product: { id: 2, title: '新商品' }, skus: [{ id: 21 }] } } }),
    state,
    () => current === 'new'
  )
  await newCall
  assert.equal(state.product.value.id, 2)
  releaseOld()
  const oldResult = await oldCall
  assert.equal(oldResult.stale, true)
  assert.equal(state.product.value.id, 2, '旧响应不得覆盖新商品')
  assert.deepEqual(state.skus.value, [{ id: 21 }])
  assert.equal(state.loading.value, false)
})

test('T28 复制工具与错误态入口存在，不再预填默认手机号', () => {
  const source = detailSource
  assert.ok(source.includes("from '@shared/clipboard.mjs'"), '必须复用共享剪贴板工具')
  assert.ok(source.includes('errorMsg') && source.includes('retry++'), '失败必须提供错误态与重试')
  assert.ok(source.includes('旧响应') || source.includes('sequence === loadSequence'), '必须使用请求序号')
  assert.equal(source.includes('13912345678'), false, '不得预填默认手机号')
  assert.equal(source.includes('复制失败，请手动长按复制：'), false, '失败提示不得拼接原文')
})

test('代付等视图绑定声明必须早于watch immediate执行', () => {
  const watchIndex = detailSource.indexOf('watch([() => route.params.id, retry]')
  assert.ok(watchIndex >= 0, '未找到路由watch')
  for (const name of ['peerPayModalOpen', 'generatingPeerPay', 'peerPayResult', 'peerMessage', 'quickMessages', 'isWeChat', 'showWechatGuide']) {
    const declaration = detailSource.indexOf(`const ${name} =`)
    assert.ok(declaration >= 0 && declaration < watchIndex, `${name} 必须在 watch 之前声明`)
  }
  assert.equal(detailSource.split('const peerPayResult =').length - 1, 1, 'peerPayResult 不得重复声明')
})

test('F11 详情图文解析容错：非法输入返回空数组，仅保留image/text白名单', () => {
  assert.deepEqual(parseDetailImages(null), [])
  assert.deepEqual(parseDetailImages(''), [])
  assert.deepEqual(parseDetailImages(123), [])
  assert.deepEqual(parseDetailImages('not-json'), [])
  assert.deepEqual(parseDetailImages('{"a":1}'), [])
  assert.deepEqual(parseDetailImages('[1,"x",null,{},[]]'), [])
  assert.deepEqual(parseDetailImages('[{"type":"video","url":"/v.mp4"}]'), [])
  assert.deepEqual(parseDetailImages('[{"type":"image"},{"type":"text"}]'), [])
  assert.deepEqual(
    parseDetailImages('[{"type":"image","url":"/a.jpg","xss":"<script>"},{"type":"text","content":"<b>加粗</b>按纯文本展示"}]'),
    [{ type: 'image', url: '/a.jpg' }, { type: 'text', content: '<b>加粗</b>按纯文本展示' }])
})

test('F11 售罄判定：单SKU与整品，缺省/非数按不可售处理', () => {
  assert.equal(isSkuSoldOut({ stockQuantity: 0 }), true)
  assert.equal(isSkuSoldOut({ stockQuantity: -1 }), true)
  assert.equal(isSkuSoldOut({}), true)
  assert.equal(isSkuSoldOut(null), true)
  assert.equal(isSkuSoldOut({ stockQuantity: 'abc' }), true)
  assert.equal(isSkuSoldOut({ stockQuantity: 5 }), false)
  assert.equal(isProductSoldOut([]), true)
  assert.equal(isProductSoldOut(null), true)
  assert.equal(isProductSoldOut([{ stockQuantity: 0 }, { stockQuantity: 0 }]), true)
  assert.equal(isProductSoldOut([{ stockQuantity: 0 }, { stockQuantity: 2 }]), false)
})

test('F11 详情页渲染侧不引入v-html，图文区按白名单渲染', () => {
  assert.equal(detailSource.includes('v-html='), false, '详情图文禁止使用v-html指令渲染')
  assert.ok(detailSource.includes('detailItems'), '必须经parseDetailImages容错解析后渲染')
})


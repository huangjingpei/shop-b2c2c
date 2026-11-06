/*
 * 文件：publicCatalog.test.mjs
 * 用途：T30 公开货源大厅规则测试：路由关键词/类型归一、类目与关键词组合请求、业务失败错误态、迟到响应守卫与承诺文案契约。
 * 维护注意：注入请求函数并读取本地源码文本，不访问网络、不挂载Vue组件；真实搜索排序、分页与浏览器渲染仍需人工验证。
 * 修改 Home/Navbar/Store/ProductDetail/App/PeerPayCashier 的搜索或文案时应同步更新本文件。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { GOODS_TYPE_BY_SEARCH, loadPublicCatalog, normalizeCategoryId, readCatalogQuery } from '../shop-storefront-vue/src/utils/publicCatalog.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'shop-storefront-vue', 'src')
const read = path => readFileSync(join(root, path), 'utf8')
const ok = data => ({ data: { code: 200, data } })

test('导航搜索类型只映射到后端白名单内的 goodsType', () => {
  assert.deepEqual(GOODS_TYPE_BY_SEARCH, { all: null, physical: 1, virtual: 2 })
})

test('路由 query 关键词去首尾空白，空串视为未搜索', () => {
  assert.deepEqual(readCatalogQuery({ q: '  蓝牙耳机  ' }), { keyword: '蓝牙耳机', goodsType: null })
  assert.deepEqual(readCatalogQuery({ q: '   ' }), { keyword: null, goodsType: null })
  assert.deepEqual(readCatalogQuery({}), { keyword: null, goodsType: null })
})

test('非法 goodsType 按全站处理，合法类型保留数字', () => {
  assert.deepEqual(readCatalogQuery({ goodsType: '2' }), { keyword: null, goodsType: 2 })
  assert.deepEqual(readCatalogQuery({ goodsType: '4' }), { keyword: null, goodsType: null })
  assert.deepEqual(readCatalogQuery({ goodsType: 'abc' }), { keyword: null, goodsType: null })
})

test('类目编号只接受正整数，非法值按全部类目处理', () => {
  assert.equal(normalizeCategoryId('7'), 7)
  assert.equal(normalizeCategoryId(7), 7)
  for (const value of [null, '', 0, -1, '1.2', 'abc']) assert.equal(normalizeCategoryId(value), null)
})

test('类目、关键词与商品类型一起传给公开列表', async () => {
  const calls = []
  const result = await loadPublicCatalog({ categoryId: 7, keyword: ' 耳机 ', goodsType: 1 }, async (...args) => {
    calls.push(args)
    return args[0].includes('/categories') ? ok([{ id: 7 }]) : ok([{ id: 3 }])
  })
  assert.deepEqual(calls, [
    ['/api/v1/public/categories'],
    ['/api/v1/public/products', { params: { categoryId: 7, q: '耳机', goodsType: 1 } }]
  ])
  assert.deepEqual(result, { categories: [{ id: 7 }], products: [{ id: 3 }], error: '' })
})

test('未搜索不发送空 q 或非法类型参数', async () => {
  const calls = []
  await loadPublicCatalog({ keyword: '   ', goodsType: 4 }, async (...args) => {
    calls.push(args[1])
    return ok([])
  })
  assert.deepEqual(calls, [undefined, { params: {} }])
})

test('业务失败返回错误态而不是把失败响应当空列表', async () => {
  const result = await loadPublicCatalog({ keyword: '耳机' }, async url =>
    url.includes('/categories') ? ok([]) : { data: { code: 500, data: [{ id: 3 }] } })
  assert.deepEqual(result, { categories: [], products: [], error: '商品列表加载失败，请稍后重试' })
})

test('网络失败不泄露异常内容，分类失败与商品成功可并存', async () => {
  const network = await loadPublicCatalog({}, async () => { throw new Error('private-token') })
  assert.equal(network.error, '商品分类与商品列表加载失败，请稍后重试')
  assert.deepEqual(network.products, [])

  const partial = await loadPublicCatalog({}, async url => {
    if (url.includes('/categories')) throw new Error('down')
    return ok([{ id: 3 }])
  })
  assert.deepEqual(partial, { categories: [], products: [{ id: 3 }], error: '商品分类加载失败，请稍后重试' })
})

const home = read('views/Home.vue')
const navbar = read('components/Navbar.vue')
const storePage = read('views/Store.vue')
const detail = read('views/ProductDetail.vue')
const app = read('App.vue')
const cashier = read('views/PeerPayCashier.vue')
const promiseWords = ['秒发', '直结', '二清', '专户存管', '免照开店', '持牌', '顺丰', '圆通']

test('Home 读取 route.query 并在条件变化时重新加载', () => {
  assert.ok(home.includes('readCatalogQuery(route.query)'))
  assert.ok(home.includes("watch(\n  () => [route.query.q, route.query.goodsType]"))
  assert.ok(home.includes('if (sequence !== requestSequence) return'))
  assert.ok(home.includes('{{ errorMsg }}') && home.includes('@click="loadData()"'))
})

test('Home 业务失败显示错误态，空结果区分搜索与类目', () => {
  assert.equal(home.includes('console.error(e)'), false)
  assert.ok(home.includes('未找到与'))
  assert.ok(home.includes('该类目下暂无在售商品'))
})

test('Navbar 搜索按钮把类型映射为 goodsType 而不是只传 q', () => {
  assert.ok(navbar.includes('GOODS_TYPE_BY_SEARCH[searchType.value]'))
  assert.ok(navbar.includes('query.goodsType = String(goodsType)'))
  assert.equal(navbar.includes('query: { q: keyword.value.trim() }'), false)
})

test('店内搜索把关键词交给本店商品接口', () => {
  assert.ok(storePage.includes('loadStorePage(id, axios.get, appliedKeyword.value)'))
  assert.ok(storePage.includes('本店未找到与'))
})

const body = source => source.replace(/<!--[\s\S]*?-->/g, '')

test('首页、导航、店铺、详情与收银台不再宣传未开通能力', () => {
  for (const [name, source] of [['Home', home], ['Navbar', navbar], ['Store', storePage], ['ProductDetail', detail], ['App', app], ['PeerPayCashier', cashier]]) {
    const template = body(source)
    for (const word of promiseWords) {
      assert.equal(template.includes(word), false, `${name} 仍包含未验证承诺：${word}`)
    }
  }
})

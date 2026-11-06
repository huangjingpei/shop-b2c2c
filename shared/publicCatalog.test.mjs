/*
 * 文件：publicCatalog.test.mjs
 * 用途：公开货源大厅规则测试：频道→类目/商品类型映射、路由关键词归一、类目与关键词组合请求、业务失败错误态、迟到响应守卫与承诺文案契约。
 * 维护注意：注入请求函数并读取本地源码文本，不访问网络、不挂载Vue组件；真实搜索排序、分页与浏览器渲染仍需人工验证。
 * 修改 Home/Navbar/Store/ProductDetail/App/PeerPayCashier 的搜索、频道或文案时应同步更新本文件。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { CATALOG_CHANNELS, GOODS_TYPE_BY_SEARCH, channelByKey, loadPublicCatalog, normalizeCategoryId, readCatalogQuery } from '../shop-storefront-vue/src/utils/publicCatalog.mjs'
import { resolveMerchantPortalUrl } from '../shop-storefront-vue/src/utils/portalLinks.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'shop-storefront-vue', 'src')
const read = path => readFileSync(join(root, path), 'utf8')
const ok = data => ({ data: { code: 200, data } })
const allQuery = { keyword: null, channelKey: 'all', channelLabel: '全品类大厅', goodsType: null, categoryIds: null }

test('导航搜索类型只映射到后端白名单内的 goodsType', () => {
  assert.deepEqual(GOODS_TYPE_BY_SEARCH, { all: null, physical: 1, virtual: 2 })
})

test('频道列表覆盖顶部导航六项，键值唯一', () => {
  assert.deepEqual(CATALOG_CHANNELS.map(c => c.label), [
    '全品类大厅', '源头厂货直发', '3C数码潮品', '服装鞋包百货', '食品生鲜批发', '虚拟数字权益'
  ])
  assert.equal(new Set(CATALOG_CHANNELS.map(c => c.key)).size, CATALOG_CHANNELS.length)
})

test('路由 query 关键词去首尾空白，空串视为未搜索', () => {
  assert.deepEqual(readCatalogQuery({ q: '  蓝牙耳机  ' }), { ...allQuery, keyword: '蓝牙耳机' })
  assert.deepEqual(readCatalogQuery({ q: '   ' }), allQuery)
  assert.deepEqual(readCatalogQuery({}), allQuery)
})

test('非法 goodsType 按全站处理，合法类型保留数字', () => {
  assert.deepEqual(readCatalogQuery({ goodsType: '2' }), { ...allQuery, goodsType: 2 })
  assert.deepEqual(readCatalogQuery({ goodsType: '4' }), allQuery)
  assert.deepEqual(readCatalogQuery({ goodsType: 'abc' }), allQuery)
})

test('频道映射到类目组或商品类型，非法频道回退全品类', () => {
  const digital = readCatalogQuery({ channel: 'digital' })
  assert.equal(digital.channelKey, 'digital')
  assert.deepEqual(digital.categoryIds, [3, 4])
  assert.equal(digital.goodsType, null)

  assert.equal(readCatalogQuery({ channel: 'physical' }).goodsType, 1)
  assert.equal(readCatalogQuery({ channel: 'virtual' }).goodsType, 2)
  assert.deepEqual(readCatalogQuery({ channel: 'apparel' }).categoryIds, [5, 6])
  assert.deepEqual(readCatalogQuery({ channel: 'food' }).categoryIds, [7])

  assert.deepEqual(readCatalogQuery({ channel: 'unknown' }), allQuery)
  assert.equal(channelByKey('food').label, '食品生鲜批发')
})

test('频道优先于手写 goodsType，避免 URL 组合出现两套筛选口径', () => {
  const mixed = readCatalogQuery({ channel: 'virtual', goodsType: '1' })
  assert.equal(mixed.goodsType, 2)
  assert.deepEqual(mixed.categoryIds, null)
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

test('频道多类目以逗号序列发送，且不再同时发送单类目参数', async () => {
  const calls = []
  await loadPublicCatalog({ categoryIds: [3, 4], categoryId: 7, keyword: '耳机' }, async (...args) => {
    calls.push(args[1])
    return ok([])
  })
  assert.deepEqual(calls, [undefined, { params: { categoryIds: '3,4', q: '耳机' } }])
})

test('非法类目编号不会进入请求参数', async () => {
  const calls = []
  await loadPublicCatalog({ categoryIds: [0, -1, 'abc'] }, async (...args) => {
    calls.push(args[1])
    return ok([])
  })
  assert.deepEqual(calls, [undefined, { params: {} }])
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
const body = source => source.replace(/<!--[\s\S]*?-->/g, '')

test('Home 读取 route.query，频道切换与条件变化都会重新加载', () => {
  assert.ok(home.includes('readCatalogQuery(route.query)'))
  assert.ok(home.includes('() => [route.query.q, route.query.channel, route.query.goodsType]'))
  assert.ok(home.includes('if (sequence !== requestSequence) return'))
  assert.ok(home.includes('{{ errorMsg }}') && home.includes('@click="loadData()"'))
})

test('分类入口收敛为顶部导航一处，首页不再重复类目树或标签行', () => {
  assert.equal(home.includes('行业主营类目'), false)
  assert.equal(home.includes('selectCategory'), false)
  assert.equal(home.includes('全部商品（'), false)
  assert.ok(home.includes('activeChannel.channelLabel'))
})

test('Home 业务失败显示错误态，空结果区分搜索与频道', () => {
  assert.equal(home.includes('console.error(e)'), false)
  assert.ok(home.includes('未找到与'))
  assert.ok(home.includes('频道下暂无在售商品'))
  assert.ok(home.includes('查看全品类'))
})

test('Navbar 搜索按钮把类型映射为 goodsType 而不是只传 q', () => {
  assert.ok(navbar.includes('GOODS_TYPE_BY_SEARCH[searchType.value]'))
  assert.ok(navbar.includes('query.goodsType = String(goodsType)'))
  assert.equal(navbar.includes('query: { q: keyword.value.trim() }'), false)
})

test('Navbar 频道导航使用统一频道定义并带选中态', () => {
  assert.ok(navbar.includes('CATALOG_CHANNELS'))
  assert.ok(navbar.includes('channelRoute(channel)'))
  assert.ok(navbar.includes('activeChannelKey === channel.key'))
  assert.ok(navbar.includes('channel.key === \'all\' ? {} : { channel: channel.key }'))
})

test('Navbar 不暴露平台管理中心，商家工作台地址可配置', () => {
  assert.equal(body(navbar).includes('平台管理中心'), false)
  assert.ok(navbar.includes('VITE_MERCHANT_PORTAL_URL'))
  assert.ok(navbar.includes('resolveMerchantPortalUrl'))
})

test('商家工作台地址：显式配置优先，开发态指向管理端3001，生产回退同源登录页', () => {
  assert.equal(resolveMerchantPortalUrl({ configured: ' https://ops.example.com/entry ', dev: true }), 'https://ops.example.com/entry')
  assert.equal(resolveMerchantPortalUrl({ dev: true, protocol: 'http:', hostname: '192.168.3.148' }), 'http://192.168.3.148:3001/login')
  assert.equal(resolveMerchantPortalUrl({ dev: false }), '/console/login')
})

test('店内搜索把关键词交给本店商品接口', () => {
  assert.ok(storePage.includes('loadStorePage(id, axios.get, appliedKeyword.value)'))
  assert.ok(storePage.includes('本店未找到与'))
})

test('首页、导航、店铺、详情与收银台不再宣传未开通能力', () => {
  for (const [name, source] of [['Home', home], ['Navbar', navbar], ['Store', storePage], ['ProductDetail', detail], ['App', app], ['PeerPayCashier', cashier]]) {
    const template = body(source)
    for (const word of promiseWords) {
      assert.equal(template.includes(word), false, `${name} 仍包含未验证承诺：${word}`)
    }
  }
})

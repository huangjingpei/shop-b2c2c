/*
 * 文件：publicCatalog.mjs
 * 用途：公开货源大厅的查询参数归一与列表加载规则；把 URL 关键词、频道、商品类型与类目组合成后端公开列表请求。
 * 维护注意：
 * 1. 关键词只做 trim，长度与类型白名单由后端裁决（超限返回 400），前端不得自行截断或伪造空结果；
 * 2. 商品/分类响应必须 code===200 且 data 为数组才采用，业务失败返回错误态而不是空列表；
 * 3. 频道（CATALOG_CHANNELS）是前台唯一分类入口，频道到类目/商品类型的映射只在此维护；一个频道可覆盖多个类目，
 *    以逗号序列传给后端 categoryIds，后端拒绝与 categoryId 同时使用；
 * 4. 非法 channel 回退“全品类”，保证手改 URL 不会整页报错；
 * 5. 请求失效由调用方用递增序号守卫，迟到的响应不得覆盖当前条件。
 */

/** 导航搜索类型 → 后端 goodsType；全站不传类型参数。 */
export const GOODS_TYPE_BY_SEARCH = Object.freeze({ all: null, physical: 1, virtual: 2 })

/** 顶部频道：label 用于导航文案，filters 用于公开列表请求；新增/调整频道须同步 shared/publicCatalog.test.mjs。 */
export const CATALOG_CHANNELS = Object.freeze([
  { key: 'all', label: '全品类大厅', filters: {} },
  { key: 'physical', label: '源头厂货直发', filters: { goodsType: 1 } },
  { key: 'digital', label: '3C数码潮品', filters: { categoryIds: [3, 4] } },
  { key: 'apparel', label: '服装鞋包百货', filters: { categoryIds: [5, 6] } },
  { key: 'food', label: '食品生鲜批发', filters: { categoryIds: [7] } },
  { key: 'virtual', label: '虚拟数字权益', filters: { goodsType: 2 } }
])

/** 只接受白名单频道键值；空值或非法值按“全品类”处理。 */
export function channelByKey(value) {
  if (typeof value !== 'string') return CATALOG_CHANNELS[0]
  return CATALOG_CHANNELS.find(channel => channel.key === value) || CATALOG_CHANNELS[0]
}

function validGoodsType(value) {
  return value === 1 || value === 2 ? value : null
}

/**
 * 从路由 query 读取搜索条件。
 * 频道优先于手写的 goodsType：频道切换后以频道定义为准；无频道时保留搜索框的 goodsType。
 * 关键词为空时按未搜索处理。
 */
export function readCatalogQuery(query = {}) {
  const keyword = typeof query.q === 'string' ? query.q.trim() || null : null
  const channel = channelByKey(query.channel)
  const rawType = query.goodsType === undefined || query.goodsType === null ? NaN : Number(query.goodsType)
  let goodsType = null
  if (channel.filters.goodsType !== undefined) {
    goodsType = channel.filters.goodsType
  } else if (channel.filters.categoryIds === undefined) {
    // 频道未限定商品类型时才采用搜索框类型；多类目频道只按类目筛选，避免两套口径叠加。
    goodsType = validGoodsType(rawType)
  }
  return {
    keyword,
    channelKey: channel.key,
    channelLabel: channel.label,
    goodsType,
    categoryIds: channel.filters.categoryIds ? [...channel.filters.categoryIds] : null
  }
}

/** 类目编号只接受正整数；非法值按“全部类目”处理。 */
export function normalizeCategoryId(value) {
  if (value === null || value === undefined || value === '') return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function readList(response) {
  if (!response || response.data?.code !== 200 || !Array.isArray(response.data.data)) return null
  return response.data.data
}

/**
 * 读取分类与公开商品列表。
 * categoryIds 优先于单个 categoryId（后端不允许两者同时出现）；非法类目编号会被丢弃。
 * @param {{categoryId?: number|null, categoryIds?: number[]|null, keyword?: string|null, goodsType?: number|null}} params
 * @param {(url: string, config?: object) => Promise<any>} fetchJson axios 兼容请求函数
 * @returns {Promise<{categories: any[], products: any[], error: string}>}
 */
export async function loadPublicCatalog({ categoryId = null, categoryIds = null, keyword = null, goodsType = null }, fetchJson) {
  const productParams = {}
  const ids = Array.isArray(categoryIds)
    ? categoryIds.map(normalizeCategoryId).filter(id => id !== null)
    : []
  if (ids.length) {
    productParams.categoryIds = ids.join(',')
  } else {
    const category = normalizeCategoryId(categoryId)
    if (category !== null) productParams.categoryId = category
  }
  if (typeof keyword === 'string' && keyword.trim()) productParams.q = keyword.trim()
  if (goodsType === 1 || goodsType === 2) productParams.goodsType = goodsType

  const [categoryResult, productResult] = await Promise.allSettled([
    fetchJson('/api/v1/public/categories'),
    fetchJson('/api/v1/public/products', { params: productParams })
  ])
  const categoryList = categoryResult.status === 'fulfilled' ? readList(categoryResult.value) : null
  const productList = productResult.status === 'fulfilled' ? readList(productResult.value) : null

  const failed = []
  if (categoryList === null) failed.push('商品分类')
  if (productList === null) failed.push('商品列表')
  return {
    categories: categoryList || [],
    products: productList || [],
    error: failed.length ? `${failed.join('与')}加载失败，请稍后重试` : ''
  }
}

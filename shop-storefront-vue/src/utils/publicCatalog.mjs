/*
 * 文件：publicCatalog.mjs
 * 用途：T30 公开货源大厅的查询参数归一与列表加载规则；把 URL 关键词、商品类型与类目组合成后端公开列表请求。
 * 维护注意：关键词只做 trim，长度与类型白名单由后端裁决（超限返回 400），前端不得自行截断或伪造空结果；
 * 商品/分类响应必须 code===200 且 data 为数组才采用，业务失败返回错误态而不是空列表；本模块只读，不缓存、不写状态。
 * 请求失效由调用方用递增序号守卫，迟到的响应不得覆盖当前条件。
 */

/** 导航搜索类型 → 后端 goodsType；全站不传类型参数。 */
export const GOODS_TYPE_BY_SEARCH = Object.freeze({ all: null, physical: 1, virtual: 2 })

/**
 * 从路由 query 读取搜索条件。
 * 非法 goodsType 视为“全站”，避免用户手改 URL 后整页报错；关键词为空时按未搜索处理。
 */
export function readCatalogQuery(query = {}) {
  const keyword = typeof query.q === 'string' ? query.q.trim() || null : null
  const rawType = query.goodsType === undefined || query.goodsType === null ? NaN : Number(query.goodsType)
  const goodsType = rawType === 1 || rawType === 2 ? rawType : null
  return { keyword, goodsType }
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
 * @param {{categoryId?: number|null, keyword?: string|null, goodsType?: number|null}} params
 * @param {(url: string, config?: object) => Promise<any>} fetchJson axios 兼容请求函数
 * @returns {Promise<{categories: any[], products: any[], error: string}>}
 */
export async function loadPublicCatalog({ categoryId = null, keyword = null, goodsType = null }, fetchJson) {
  const productParams = {}
  const category = normalizeCategoryId(categoryId)
  if (category !== null) productParams.categoryId = category
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

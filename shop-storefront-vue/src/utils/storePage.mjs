/*
 * 文件：storePage.mjs
 * 用途：独立读取公开店铺与本店商品，统一空店、不可用和读取失败的展示状态。
 * 维护注意：ID保持十进制字符串避免大整数损失；同时检查HTTP与业务响应。商品读取失败保留已取得的真实店铺资料，禁止回退演示数据或记录完整请求异常。
 */
export async function loadStorePage(id, get) {
  const state = { store: null, products: [], error: '', productsError: '' }
  if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || BigInt(id) > 9223372036854775807n) {
    return { ...state, error: '店铺编号不正确' }
  }
  try {
    const { data } = await get(`/api/v1/public/stores/${id}`)
    if (data?.code === 404) return { ...state, error: '店铺不存在或暂未营业' }
    if (data?.code !== 200 || !data.data || String(data.data.id) !== id) throw new Error('Invalid store response')
    state.store = data.data
  } catch {
    return { ...state, error: '店铺资料加载失败，请稍后重试' }
  }
  try {
    const { data } = await get('/api/v1/public/products', { params: { merchantId: id } })
    if (data?.code !== 200 || !Array.isArray(data.data)
        || data.data.some(p => !p || String(p.merchantId) !== id)) throw new Error('Invalid product response')
    state.products = data.data
  } catch {
    state.productsError = '商品加载失败，请稍后重试'
  }
  return state
}

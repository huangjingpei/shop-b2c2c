/*
 * 文件：productDetail.mjs
 * 用途：商品详情加载状态机，集中重置旧视图、校验详情响应并保证 loading 复位。
 * 维护注意：重置与写入必须包在 try/finally 内，加载失败不得保留旧商品或旧成功弹窗；本模块只处理状态，不发起网络请求，也不替代服务端商品可售校验。视图 ref 由调用方一次性传入，避免在 setup 阶段提前访问尚未初始化的绑定。
 * T28：可选 isCurrent 回调用于请求序号/路由失效控制——过期响应不得写入状态，也不得复位新请求的 loading。
 */
export function resetProductDetailState(state) {
  state.product.value = null
  state.skus.value = []
  state.store.value = null
  state.currentSku.value = null
  state.successResult.value = null
  state.peerPayResult.value = null
}

export function applyProductDetail(state, payload) {
  const product = payload?.product
  if (!product || typeof product !== 'object' || product.id == null) {
    throw new Error('商品详情响应缺少有效商品')
  }
  const skus = Array.isArray(payload.skus) ? payload.skus : []
  state.product.value = product
  state.skus.value = skus
  state.store.value = payload.store ?? null
  state.currentSku.value = skus.length > 0 ? skus[0] : null
}

export async function loadProductDetail(id, fetchDetail, state, isCurrent = () => true) {
  if (!id) return { ok: false, error: null }
  if (!isCurrent()) return { ok: false, error: null, stale: true }
  state.loading.value = true
  try {
    resetProductDetailState(state)
    const response = await fetchDetail(id)
    // T28：快速切换商品/路由时，旧响应不得覆盖新商品，也不得提前结束新请求的 loading。
    if (!isCurrent()) return { ok: false, error: null, stale: true }
    applyProductDetail(state, response?.data?.data)
    return { ok: true, error: null }
  } catch (error) {
    if (!isCurrent()) return { ok: false, error: null, stale: true }
    return { ok: false, error }
  } finally {
    if (isCurrent()) state.loading.value = false
  }
}

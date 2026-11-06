/*
 * 文件：merchantProducts.mjs
 * 用途：统一解析 /merchant/products 的 {product, skus} 契约，供商品列表、工作台统计与卡密导入页复用。
 * 维护注意：后端已固定返回包装对象，任何页面都不得再把原始行当作商品；遇到缺失product的脏数据直接跳过，避免模板访问空对象。只做视图数据整形，不承担租户过滤。
 * T37：商品编号统一按字符串比较，避免大整数 ID 在数值比较时失真。
 */
export function merchantProductRows(rows) {
  if (!Array.isArray(rows)) return []
  return rows.map(row => row?.product).filter(product => product && product.id != null)
}

export function skusForMerchantProduct(rows, productId) {
  if (!Array.isArray(rows)) return []
  const row = rows.find(item => item?.product?.id != null && String(item.product.id) === String(productId))
  return Array.isArray(row?.skus) ? row.skus : []
}

/*
 * 文件：productEditing.mjs
 * 用途：商品发布初始库存、编辑表单快照、提交白名单、上下架规则及SKU增删校验。
 * 维护注意：自动卡密发布数量为0，由导入生成；库存/类型/归属/规格编码不进入编辑请求。revision必须来自服务端快照；前端规则不替代后端事务、租户和价格校验。SKU删除按钮只在零库存且非最后一个时可用，历史记录拦截由后端兜底。
 */
export function editorForm(snapshot) {
  const p = snapshot.product
  return { revision: snapshot.revision, categoryId: p.categoryId, title: p.title, subTitle: p.subTitle ?? '',
    mainImage: p.mainImage, serviceDesc: p.serviceDesc ?? '',
    skus: snapshot.skus.map(s => ({ id: s.id, skuCode: s.skuCode, specData: s.specData, price: Number(s.price), stockQuantity: s.stockQuantity })) }
}
export function productCreationPayload(form) {
  return { ...form, skus: form.skus.map(s => ({ ...s, stockQuantity: form.goodsType === 2 ? 0 : s.stockQuantity })) }
}
export function updatePayload(form) {
  if (!form.title?.trim() || !form.mainImage?.trim() || !(Number(form.categoryId) > 0) || !form.revision || !form.skus?.length) {
    throw new Error('请填写类目、商品标题、主图和SKU售价')
  }
  const ids = new Set()
  const skus = form.skus.map(s => {
    const price = Number(s.price)
    if (!(Number(s.id) > 0) || ids.has(String(s.id)) || !Number.isFinite(price) || price <= 0 || price > 99999999.99
      || Math.abs(price * 100 - Math.round(price * 100)) > 0.000001) throw new Error('SKU售价须为正数、最多两位小数，且SKU不能重复')
    ids.add(String(s.id))
    return { id: s.id, price }
  })
  return { revision: form.revision, categoryId: form.categoryId, title: form.title, subTitle: form.subTitle,
    mainImage: form.mainImage, serviceDesc: form.serviceDesc, skus }
}
export function skuCreatePayload(goodsType, draft) {
  const skuCode = String(draft?.skuCode ?? '').trim()
  if (!skuCode || skuCode.length > 64) throw new Error('SKU编码必填且不超过64字符')
  const specData = String(draft?.specData ?? '').trim()
  if (!specData || specData.length > 255) throw new Error('规格须为JSON对象且不超过255字符')
  try {
    const parsed = JSON.parse(specData)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error()
  } catch { throw new Error('规格须为JSON对象且不超过255字符') }
  const price = Number(draft?.price)
  if (!Number.isFinite(price) || price <= 0 || price > 99999999.99
    || Math.abs(price * 100 - Math.round(price * 100)) > 0.000001) throw new Error('SKU售价须为正数、最多两位小数')
  let stockQuantity = Number(draft?.stockQuantity ?? 0)
  if (Number(goodsType) === 2) stockQuantity = 0
  if (!Number.isInteger(stockQuantity) || stockQuantity < 0) throw new Error('初始库存须为非负整数；自动卡密必须为0')
  return { skuCode, specData, price, stockQuantity }
}
export function canRemoveSku(sku, skuCount) {
  return Number(skuCount) > 1 && Number(sku?.stockQuantity) === 0
}
export function statusAction(product) {
  if (product.status === 1) return { label: '下架', target: 0 }
  if (product.status === 0) return { label: '上架', target: 1 }
  return null
}
export function productStatusLabel(status) {
  return ({ 0: '下架', 1: '在售', 2: '违规下架' })[status] ?? '未知状态'
}

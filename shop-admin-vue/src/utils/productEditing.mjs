/*
 * 文件：productEditing.mjs
 * 用途：商品编辑表单快照、提交白名单和上下架展示规则。
 * 维护注意：库存/类型/归属/规格编码只用于展示，不进入更新请求；revision必须来自服务端快照，不能从列表伪造。前端校验不替代后端事务、租户和价格校验。
 */
export function editorForm(snapshot) {
  const p = snapshot.product
  return { revision: snapshot.revision, categoryId: p.categoryId, title: p.title, subTitle: p.subTitle ?? '',
    mainImage: p.mainImage, serviceDesc: p.serviceDesc ?? '',
    skus: snapshot.skus.map(s => ({ id: s.id, skuCode: s.skuCode, specData: s.specData, price: Number(s.price), stockQuantity: s.stockQuantity })) }
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
export function statusAction(product) {
  if (product.status === 1) return { label: '下架', target: 0 }
  if (product.status === 0) return { label: '上架', target: 1 }
  return null
}
export function productStatusLabel(status) {
  return ({ 0: '下架', 1: '在售', 2: '违规下架' })[status] ?? '未知状态'
}

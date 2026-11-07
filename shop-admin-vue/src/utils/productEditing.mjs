/*
 * 文件：productEditing.mjs
 * 用途：商品发布初始库存、编辑表单快照、提交白名单、上下架、SKU增删及规格维护校验。
 * 维护注意：自动卡密发布数量为0，由导入生成；库存/类型/归属/SKU编码不进入编辑请求。revision必须来自服务端快照；前端规则不替代后端事务、租户和价格校验。SKU删除按钮只在零库存且非最后一个时可用，历史记录拦截由后端兜底；规格维护只提交spec_data，经行内单独保存。详情图文(detailImages)为image/text白名单条目，最多20条，url 1..255且须http(s)://或/开头，content 1..2000，口径与后端normalizeDetailImages一致；空数组提交为null（清空）。
 */
export const DETAIL_ITEM_MAX = 20
export const DETAIL_URL_MAX = 255
export const DETAIL_TEXT_MAX = 2000

/** 解析服务端detail_images JSON串为编辑条目；非法/空返回[]，绝不抛出（容错读取）。 */
export function parseDetailImages(json) {
  if (!json || typeof json !== 'string') return []
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(item => item && typeof item === 'object')
      .map(item => item.type === 'image'
        ? { type: 'image', url: String(item.url ?? ''), content: '' }
        : { type: 'text', url: '', content: String(item.content ?? '') })
      .filter(item => (item.type === 'image' ? item.url : item.content))
  } catch { return [] }
}

/** 校验并规范化详情图文条目，非法抛Error；空数组返回null（清空语义）。 */
export function detailImagesPayload(items) {
  const list = Array.isArray(items) ? items : []
  if (list.length === 0) return null
  if (list.length > DETAIL_ITEM_MAX) throw new Error(`详情图文最多 ${DETAIL_ITEM_MAX} 条`)
  return list.map(item => {
    if (!item || (item.type !== 'image' && item.type !== 'text')) throw new Error('详情图文仅支持图片或文字条目')
    if (item.type === 'image') {
      const url = String(item.url ?? '').trim()
      if (!url || url.length > DETAIL_URL_MAX || !(url.startsWith('https://') || url.startsWith('http://') || url.startsWith('/')))
        throw new Error(`详情图片地址必填、不超过${DETAIL_URL_MAX}字符，且须以 http(s):// 或 / 开头`)
      return { type: 'image', url }
    }
    const content = String(item.content ?? '').trim()
    if (!content || content.length > DETAIL_TEXT_MAX) throw new Error(`详情文字必填且不超过${DETAIL_TEXT_MAX}字符`)
    return { type: 'text', content }
  })
}

export function editorForm(snapshot) {
  const p = snapshot.product
  return { revision: snapshot.revision, categoryId: p.categoryId, title: p.title, subTitle: p.subTitle ?? '',
    mainImage: p.mainImage, serviceDesc: p.serviceDesc ?? '', detailImages: parseDetailImages(p.detailImages),
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
    mainImage: form.mainImage, serviceDesc: form.serviceDesc, detailImages: detailImagesPayload(form.detailImages), skus }
}
export function skuSpecPayload(specData) {
  const spec = String(specData ?? '').trim()
  if (!spec || spec.length > 255) throw new Error('规格须为JSON对象且不超过255字符')
  try {
    const parsed = JSON.parse(spec)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error()
  } catch { throw new Error('规格须为JSON对象且不超过255字符') }
  return { specData: spec }
}
export function skuCreatePayload(goodsType, draft) {
  const skuCode = String(draft?.skuCode ?? '').trim()
  if (!skuCode || skuCode.length > 64) throw new Error('SKU编码必填且不超过64字符')
  const { specData } = skuSpecPayload(draft?.specData)
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

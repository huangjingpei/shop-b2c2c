/*
 * 文件：cardAssets.mjs
 * 用途：卡密导入白名单/批内去重、批次/库存查询参数、导出确认载荷及作废状态/权限展示规则。
 * 维护注意：卡密不得输出到日志；行首尾清理兼容Java trim的ASCII控制符，不改变大小写或内部字符。库存列表只消费后端脱敏标识，禁止把明文写入本地状态/缓存。导出载荷固定 confirm=true 且条数有上限，但仍须由后端校验角色与写审计。本地角色只管展示，历史去重、预占、状态和库存校验由后端完成。
 */
export function cardImportPayload(form) {
  const productId = Number(form.productId), skuId = Number(form.skuId)
  const cardContents = String(form.rawText ?? '').split(/\r?\n/)
    .map(value => value.trim().replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '')).filter(Boolean)
  if (!Number.isSafeInteger(productId) || productId < 1 || !Number.isSafeInteger(skuId) || skuId < 1) throw new Error('请选择本店商品与SKU')
  if (!cardContents.length || cardContents.length > 1000 || cardContents.some(value => value.length > 4096)) throw new Error('每批1至1000条卡密，每条最多4096字符')
  if (new Set(cardContents).size !== cardContents.length) throw new Error('本批卡密存在重复，请核对后再导入')
  return { productId, skuId, cardContents }
}
export function cardStatusLabel(status) { return ({ 0: '未售出（在库）', 1: '已售出', 2: '已作废' })[status] ?? '未知状态' }
export function canVoidCard(role, card) {
  return ['SUPERADMIN', 'MERCHANT_ADMIN'].includes(role) && card.status === 0 && card.orderSn == null
}
const positiveId = value => {
  if (value == null || String(value).trim() === '') return undefined
  const text = String(value).trim(), number = Number(text)
  if (!/^[1-9]\d*$/.test(text) || !Number.isSafeInteger(number)) throw new Error('SKU或分页游标须为正整数')
  return number
}
export function cardBatchQuery(skuId, beforeId) {
  return { skuId: positiveId(skuId), beforeId: positiveId(beforeId), limit: 20 }
}
export function cardListQuery(skuId, onlyAvailable, beforeId) {
  return { skuId: positiveId(skuId), onlyAvailable: Boolean(onlyAvailable), beforeId: positiveId(beforeId), limit: 20 }
}
export function cardExportPayload(skuId, onlyAvailable = true) {
  const id = positiveId(skuId)
  if (id == null) throw new Error('请输入需要导出的SKU')
  return { skuId: id, confirm: true, onlyAvailable: Boolean(onlyAvailable), limit: 500 }
}


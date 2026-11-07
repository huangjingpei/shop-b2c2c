/*
 * 文件：cardAssets.mjs
 * 用途：卡密导入白名单/批内去重、批次/库存查询参数、导出确认载荷、作废状态/权限及一致性核对行状态展示规则。
 * 维护注意：卡密不得输出到日志；行首尾清理兼容Java trim的ASCII控制符，不改变大小写或内部字符。库存列表只消费后端脱敏标识，禁止把明文写入本地状态/缓存。导出载荷固定 confirm=true 且条数有上限，但仍须由后端校验角色与写审计。本地角色只管展示，历史去重、预占、状态和库存校验由后端完成。一致性核对为只读，行状态仅用于展示高亮，不构成修复依据。
 * T37：商品/SKU/游标编号以十进制字符串请求，避免 Number() 截断超过 2^53-1 的编号；拒绝已失去精度的 number 输入。
 */
export function cardImportPayload(form) {
  const productId = positiveId(form.productId), skuId = positiveId(form.skuId)
  const cardContents = String(form.rawText ?? '').split(/\r?\n/)
    .map(value => value.trim().replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '')).filter(Boolean)
  if (productId === undefined || skuId === undefined) throw new Error('请选择本店商品与SKU')
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
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('SKU或分页游标须为安全范围内的正整数，请重新加载')
  const text = String(value).trim()
  if (!/^[1-9]\d*$/.test(text) || BigInt(text) > 9223372036854775807n) throw new Error('SKU或分页游标须为正整数')
  return text
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
/**
 * F12 一致性核对行状态：异常指纹（去重盲区）优先于账实漂移提示；
 * consistent 严格为 true 才算一致，其余按漂移保守展示，未知行返回 unknown。
 */
export function consistencyIssue(row) {
  if (row == null || typeof row !== 'object') return 'unknown'
  if (Number(row.fingerprintUnavailable) > 0) return 'unavailable'
  return row.consistent === true ? 'ok' : 'drift'
}


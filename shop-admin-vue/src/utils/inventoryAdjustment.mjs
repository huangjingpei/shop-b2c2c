/*
 * 文件：inventoryAdjustment.mjs
 * 用途：人工库存调整参数快照及管理端操作可见性规则。
 * 维护注意：只提交增减量和预期可售数，商户/操作人由服务端会话确定；卡密不可手调。页面权限仅作展示，成功后才能结束幂等提交，重放结果是历史快照。
 * T37：商品/SKU编号以十进制字符串提交，避免超过 2^53-1 的编号在 Number() 转换时被截断；拒绝已是精度丢失的 number 输入。
 */
export function canAdjustInventory(role, goodsType) {
  return ['SUPERADMIN', 'MERCHANT_ADMIN'].includes(role) && [1, 3].includes(goodsType)
}
const idText = value => {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('商品或SKU编号超出安全整数范围，请从列表重新选择')
  const text = String(value ?? '').trim()
  if (!/^[1-9]\d*$/.test(text) || BigInt(text) > 9223372036854775807n) throw new Error('商品或SKU编号须为正整数')
  return text
}
export function inventoryPayload(form) {
  const productId = idText(form.productId), skuId = idText(form.skuId), delta = Number(form.delta), expectedStock = Number(form.expectedStock)
  const reason = typeof form.reason === 'string' ? form.reason.trim() : ''
  if (!Number.isInteger(delta) || delta === 0 || Math.abs(delta) > 1000000
    || form.expectedStock == null || form.expectedStock === '' || !Number.isInteger(expectedStock) || expectedStock < 0 || expectedStock > 2147483647
    || !reason || reason.length > 255) throw new Error('请填写非零整数调整量（最多100万）、有效库存和调整原因')
  if (expectedStock + delta < 0 || expectedStock + delta > 2147483647) throw new Error('调整后可售库存超出范围')
  return { productId, skuId, delta, expectedStock, reason }
}

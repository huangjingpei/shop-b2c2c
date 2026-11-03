/*
 * 文件：inventoryAdjustment.mjs
 * 用途：人工库存调整参数快照及管理端操作可见性规则。
 * 维护注意：只提交增减量和预期可售数，商户/操作人由服务端会话确定；卡密不可手调。页面权限仅作展示，成功后才能结束幂等提交，重放结果是历史快照。
 */
export function canAdjustInventory(role, goodsType) {
  return ['SUPERADMIN', 'MERCHANT_ADMIN'].includes(role) && [1, 3].includes(goodsType)
}
export function inventoryPayload(form) {
  const productId = Number(form.productId), skuId = Number(form.skuId), delta = Number(form.delta), expectedStock = Number(form.expectedStock)
  const reason = typeof form.reason === 'string' ? form.reason.trim() : ''
  if (!Number.isSafeInteger(productId) || productId < 1 || !Number.isSafeInteger(skuId) || skuId < 1
    || !Number.isInteger(delta) || delta === 0 || Math.abs(delta) > 1000000
    || form.expectedStock == null || form.expectedStock === '' || !Number.isInteger(expectedStock) || expectedStock < 0 || expectedStock > 2147483647
    || !reason || reason.length > 255) throw new Error('请填写非零整数调整量（最多100万）、有效库存和调整原因')
  if (expectedStock + delta < 0 || expectedStock + delta > 2147483647) throw new Error('调整后可售库存超出范围')
  return { productId, skuId, delta, expectedStock, reason }
}

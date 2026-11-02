/*
 * 文件：goodsType.mjs
 * 用途：商品类型标签、定制服务履约条件及服务凭证展示的纯函数。
 * 维护注意：1实物、2自动卡密、3定制服务；未知类型不能伪装实物。两端有独立副本以便部署打包，改规则须同步两端并运行shared/goodsType.test.mjs；此处不执行后端授权。
 */
export function goodsTypeLabel(type) {
  return ({ 1: '实物商品', 2: '自动卡密', 3: '定制服务' })[type] || '未知类型'
}

export function canFulfillService(type, status) {
  return type === 3 && status === 'PAID'
}
export function serviceVoucherText(voucher) {
  return voucher || '尚未交付，请等待商家处理'
}

export function goodsTypeLabel(type) {
  return ({ 1: '实物商品', 2: '自动卡密', 3: '定制服务' })[type] || '未知类型'
}

export function canFulfillService(type, status) {
  return type === 3 && status === 'PAID'
}
export function serviceVoucherText(voucher) {
  return voucher || '尚未交付，请等待商家处理'
}

/*
 * 文件：shared/orderReceipt.mjs
 * 用途：商城端与管理端共用的实物收货地址、运费合计与确认收货规则。
 * 维护注意：
 *   - 仅 goodsType=1（实物）需要收货地址；2 卡密、3 定制服务返回 null，不显示也不提交地址。
 *   - 前端校验只用于提前提示，后端 ShippingAddress 仍会二次校验；两边错误文案与长度上限须保持一致。
 *   - 收件手机号按中国大陆手机号 1[3-9]\d{9} 校验，详细地址长度 5–200；不得把地址写入日志或错误堆栈。
 *   - canConfirmReceipt 只允许 SHIPPED；后端还会校验订单类型与领取凭证，重复提交按幂等成功。
 *   - 本文件为仓库根 shared/ 运行时模块，两端通过 vite 别名 @shared 引用；修改须同步两端构建与 node --test。
 */

export const RECEIVER_LIMITS = Object.freeze({
  nameMax: 32,
  regionMax: 64,
  addressMin: 5,
  addressMax: 200,
})

export function isPhysicalGoods(goodsType) {
  return Number(goodsType) === 1
}

function trim(value) {
  return value == null ? '' : String(value).trim()
}

/**
 * 校验并返回地址快照；非实物订单返回 null。
 * 校验失败抛出 Error（message 可直接展示给用户），不包含原始输入。
 */
export function validateReceiver(goodsType, receiver = {}) {
  if (!isPhysicalGoods(goodsType)) return null
  const receiverName = trim(receiver.receiverName)
  const receiverPhone = trim(receiver.receiverPhone)
  const receiverRegion = trim(receiver.receiverRegion)
  const receiverAddress = trim(receiver.receiverAddress)

  if (!receiverName) throw new Error('请填写收货人姓名')
  if (receiverName.length > RECEIVER_LIMITS.nameMax) throw new Error('收货人姓名长度不能超过32字符')
  if (!receiverPhone) throw new Error('请填写收货人手机号')
  if (!/^1[3-9]\d{9}$/.test(receiverPhone)) throw new Error('收货人手机号格式不正确')
  if (!receiverRegion) throw new Error('请填写收货省市区')
  if (receiverRegion.length > RECEIVER_LIMITS.regionMax) throw new Error('收货省市区长度不能超过64字符')
  if (!receiverAddress) throw new Error('请填写详细收货地址')
  if (receiverAddress.length < RECEIVER_LIMITS.addressMin || receiverAddress.length > RECEIVER_LIMITS.addressMax) {
    throw new Error('详细收货地址长度须在5到200字符之间')
  }

  return { receiverName, receiverPhone, receiverRegion, receiverAddress }
}

/** 买家确认收货入口只在已发货时可见；数字商品不会进入 SHIPPED。 */
export function canConfirmReceipt(status) {
  return String(status || '').toUpperCase() === 'SHIPPED'
}

/** 实付合计：实物订单叠加店铺固定运费，数字商品不叠加；非有限数值按 0 处理。 */
export function payableAmount(goodsAmount, shippingFee, goodsType) {
  const goods = Number(goodsAmount)
  const fee = isPhysicalGoods(goodsType) ? Number(shippingFee) : 0
  const safeGoods = Number.isFinite(goods) && goods > 0 ? goods : 0
  const safeFee = Number.isFinite(fee) && fee > 0 ? fee : 0
  return (safeGoods + safeFee).toFixed(2)
}

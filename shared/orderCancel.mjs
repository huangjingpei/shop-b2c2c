/*
 * 文件：shared/orderCancel.mjs
 * 用途：商城端取消未支付订单的展示与请求规则，与后端 OrderCancellationService 口径对齐。
 * 维护注意：
 *   - 只有 UNPAID 可取消；CLOSED/CANCELLED/REFUNDED 视为已关闭，重复取消按成功返回；其余状态不得展示取消入口。
 *   - 取消请求必须携带 orderSn、contact、queryToken，凭证只放请求体；不得拼进 URL 或日志。
 *   - 取消后会释放预占库存并尽力关闭渠道交易；前端不得自行推断库存或支付结果，须以服务端返回状态为准。
 *   - 本文件为仓库根 shared/ 运行时模块，两端通过 @shared 引用；修改须补 node --test。
 */

export function canCancelUnpaid(status) {
  return String(status || '').toUpperCase() === 'UNPAID'
}

/** 已是关闭/终态，按钮应隐藏；重复提交由服务端幂等处理。 */
export function isAlreadyClosed(status) {
  return ['CLOSED', 'CANCELLED', 'REFUNDED'].includes(String(status || '').toUpperCase())
}

/** 取消请求体：只接受查单三要素，禁止附加金额/状态等可伪造字段。 */
export function cancelRequestBody(orderSn, contact, queryToken) {
  return {
    orderSn: String(orderSn || '').trim(),
    contact: String(contact || '').trim(),
    queryToken: String(queryToken || '').trim(),
  }
}

/** 取消成功后的展示状态：服务端返回值优先，缺省按 CLOSED。 */
export function cancelledStatus(responseStatus) {
  const value = String(responseStatus || '').toUpperCase()
  return value || 'CLOSED'
}

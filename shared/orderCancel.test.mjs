/*
 * 文件：shared/orderCancel.test.mjs
 * 用途：校验取消未支付订单的展示条件、终态判断与请求体白名单。
 * 维护注意：仅内存用例，不访问网络；状态值必须与后端 TradeOrder.orderStatus 保持一致。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { canCancelUnpaid, isAlreadyClosed, cancelRequestBody, cancelledStatus } from './orderCancel.mjs'

test('仅未支付订单展示取消入口', () => {
  assert.equal(canCancelUnpaid('UNPAID'), true)
  assert.equal(canCancelUnpaid('unpaid'), true)
  for (const status of ['PAID', 'SHIPPED', 'COMPLETED', 'CLOSED', 'REFUNDED', 'CANCELLED', null, '']) {
    assert.equal(canCancelUnpaid(status), false)
  }
})

test('关闭与终态不重复展示取消', () => {
  assert.equal(isAlreadyClosed('CLOSED'), true)
  assert.equal(isAlreadyClosed('CANCELLED'), true)
  assert.equal(isAlreadyClosed('REFUNDED'), true)
  assert.equal(isAlreadyClosed('UNPAID'), false)
  assert.equal(isAlreadyClosed('PAID'), false)
})

test('取消请求体只包含查单三要素并去除空白', () => {
  assert.deepEqual(cancelRequestBody(' ORD1 ', ' 13800000000 ', ' tk '), {
    orderSn: 'ORD1',
    contact: '13800000000',
    queryToken: 'tk',
  })
  assert.deepEqual(cancelRequestBody(null, undefined, null), { orderSn: '', contact: '', queryToken: '' })
})

test('取消成功状态默认 CLOSED', () => {
  assert.equal(cancelledStatus('CLOSED'), 'CLOSED')
  assert.equal(cancelledStatus('cancelled'), 'CANCELLED')
  assert.equal(cancelledStatus(null), 'CLOSED')
})

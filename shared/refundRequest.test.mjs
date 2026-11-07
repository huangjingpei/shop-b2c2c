import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isValidAmount, refundRequestPayload, rejectPayload, refundStatusLabel, refundStatusTag, REFUNDABLE_ORDER_STATUSES } from '../shared/refundRequest.mjs'

test('isValidAmount 校验正数与两位小数', () => {
  assert.equal(isValidAmount('10.00', '100.00'), true)
  assert.equal(isValidAmount('0', '100'), false)
  assert.equal(isValidAmount('-5', '100'), false)
  assert.equal(isValidAmount('10.123', '100'), false)
  assert.equal(isValidAmount('101', '100'), false)
  assert.equal(isValidAmount('abc', '100'), false)
  assert.equal(isValidAmount('', '100'), false)
})

test('refundRequestPayload 必填与边界', () => {
  const ok = refundRequestPayload({ orderSn: 'O1', contact: '138', queryToken: 't', refundAmount: '10.00', reason: '不想要了' })
  assert.equal(ok.refundAmount, '10.00')
  assert.throws(() => refundRequestPayload({ orderSn: '', contact: '138', queryToken: 't', refundAmount: '10', reason: 'x' }))
  assert.throws(() => refundRequestPayload({ orderSn: 'O1', contact: '138', queryToken: 't', refundAmount: '10', reason: '' }))
  assert.throws(() => refundRequestPayload({ orderSn: 'O1', contact: '138', queryToken: 't', refundAmount: '10.123', reason: 'x' }))
})

test('rejectPayload 拒绝原因', () => {
  assert.equal(rejectPayload('  已发货  ').rejectReason, '已发货')
  assert.throws(() => rejectPayload(''))
})

test('状态标签映射', () => {
  assert.equal(refundStatusLabel('PENDING'), '待商家处理')
  assert.equal(refundStatusTag('REJECTED'), 'danger')
  assert.deepEqual(REFUNDABLE_ORDER_STATUSES, ['PAID', 'SHIPPED', 'COMPLETED'])
})

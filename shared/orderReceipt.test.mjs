/*
 * 文件：shared/orderReceipt.test.mjs
 * 用途：校验实物收货地址、确认收货与运费合计规则，防止前后端口径漂移。
 * 维护注意：测试只使用内存输入，不访问网络/数据库；新增规则须同时补“拒绝非法输入”和“放行合法输入”用例。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  isPhysicalGoods,
  validateReceiver,
  canConfirmReceipt,
  payableAmount,
} from './orderReceipt.mjs'

const valid = {
  receiverName: '张三',
  receiverPhone: '13800138000',
  receiverRegion: '广东省 深圳市 南山区',
  receiverAddress: '科技园南路 88 号 3 栋 501',
}

test('仅实物商品需要收货地址，数字商品返回 null', () => {
  assert.equal(isPhysicalGoods(1), true)
  assert.equal(isPhysicalGoods(2), false)
  assert.equal(isPhysicalGoods(3), false)
  assert.equal(validateReceiver(1, valid).receiverName, '张三')
  assert.equal(validateReceiver(2, {}), null)
  assert.equal(validateReceiver(3, {}), null)
})

test('实物订单四项齐全并去除首尾空格', () => {
  const result = validateReceiver(1, {
    receiverName: ' 张三 ',
    receiverPhone: ' 13800138000 ',
    receiverRegion: ' 广东省 深圳市 南山区 ',
    receiverAddress: ' 科技园南路 88 号 3 栋 501 ',
  })
  assert.deepEqual(result, {
    receiverName: '张三',
    receiverPhone: '13800138000',
    receiverRegion: '广东省 深圳市 南山区',
    receiverAddress: '科技园南路 88 号 3 栋 501',
  })
})

test('实物订单缺少任一字段均拒绝', () => {
  for (const key of ['receiverName', 'receiverPhone', 'receiverRegion', 'receiverAddress']) {
    assert.throws(() => validateReceiver(1, { ...valid, [key]: '   ' }), Error)
  }
  assert.throws(() => validateReceiver(1, {}), /请填写收货人姓名/)
})

test('手机号格式与地址长度边界', () => {
  assert.throws(() => validateReceiver(1, { ...valid, receiverPhone: '1380013800' }), /手机号格式/)
  assert.throws(() => validateReceiver(1, { ...valid, receiverPhone: '12800138000' }), /手机号格式/)
  assert.throws(() => validateReceiver(1, { ...valid, receiverName: '名'.repeat(33) }), /32/)
  assert.throws(() => validateReceiver(1, { ...valid, receiverRegion: '区'.repeat(65) }), /64/)
  assert.throws(() => validateReceiver(1, { ...valid, receiverAddress: '短' }), /5到200/)
  assert.throws(() => validateReceiver(1, { ...valid, receiverAddress: '地'.repeat(201) }), /5到200/)
  assert.doesNotThrow(() => validateReceiver(1, { ...valid, receiverAddress: '一二三四五' }))
  assert.doesNotThrow(() => validateReceiver(1, { ...valid, receiverAddress: '地'.repeat(200) }))
})

test('确认收货只允许已发货状态', () => {
  assert.equal(canConfirmReceipt('SHIPPED'), true)
  assert.equal(canConfirmReceipt('shipped'), true)
  assert.equal(canConfirmReceipt('PAID'), false)
  assert.equal(canConfirmReceipt('COMPLETED'), false)
  assert.equal(canConfirmReceipt(null), false)
})

test('运费只叠加到实物订单且非法值按 0', () => {
  assert.equal(payableAmount('99.90', '8.00', 1), '107.90')
  assert.equal(payableAmount('99.90', '8.00', 2), '99.90')
  assert.equal(payableAmount('99.90', 'abc', 1), '99.90')
  assert.equal(payableAmount('99.90', '-1', 1), '99.90')
  assert.equal(payableAmount(null, '8.00', 1), '8.00')
})

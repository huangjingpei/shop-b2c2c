/*
 * 文件：buyerReceipt.test.mjs
 * 用途：验证领取参数规范化及查单链接不带联系方式/领取凭证。
 * 维护注意：前端校验不替代服务端授权，敏感字段只能放POST请求体；新增路由参数须继续检验白名单。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { receiptRequest, receiptPath, receiptRouteQuery } from '../shop-storefront-vue/src/utils/buyerReceipt.mjs'
test('领取参数去除复制产生的首尾空格', () => {
  assert.deepEqual(receiptRequest(' ORD1 ', ' buyer ', ' private '), { orderSn: 'ORD1', contact: 'buyer', queryToken: 'private' })
})
for (const values of [['ORD1', 'buyer', ''], ['', 'buyer', 'secret'], ['ORD1', null, 'secret'], ['ORD1', 'buyer', 'x'.repeat(65)]]) {
  test(`拒绝不完整或超长的领取参数 ${JSON.stringify(values.map(value => typeof value))}`, () => {
    assert.throws(() => receiptRequest(...values))
  })
}
test('查单链接仅包含编码后的订单号', () => {
  const url = new URL(receiptPath('ORD&contact=private'), 'https://shop.example')
  assert.deepEqual([...url.searchParams.keys()], ['sn'])
  assert.equal(url.searchParams.get('sn'), 'ORD&contact=private')
  assert.equal(url.searchParams.has('contact'), false)
  assert.equal(url.searchParams.has('queryToken'), false)
})

test('旧URL只保留订单号，不从地址接收联系信息或领取凭证', () => {
  assert.deepEqual(receiptRouteQuery({ sn: 'ORD1', contact: 'private', queryToken: 'secret' }), { sn: 'ORD1' })
  assert.deepEqual(receiptRouteQuery({ queryToken: 'secret' }), {})
  assert.deepEqual(receiptRouteQuery({ sn: ['ambiguous', 'value'] }), {})
})

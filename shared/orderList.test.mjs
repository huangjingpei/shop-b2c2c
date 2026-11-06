/*
 * 文件：orderList.test.mjs
 * 用途：T24 订单 keyset 分页前端规则测试：游标原样回传、筛选切换清空旧页、hasMore/nextCursor 以服务端为准。
 * 维护注意：不按数组长度推断尾页；cursor 不透明，禁止前端解析成数字导致精度丢失。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { buildOrderQuery, mergeOrderPage, readOrderPage, orderStatusLabel } from '../shop-admin-vue/src/utils/orderList.mjs'

test('查询参数保留不透明游标并只带非空筛选', () => {
  assert.deepEqual(buildOrderQuery({ status: 'PAID', cursor: '1700000000000_42', pageSize: 20 }),
    { pageSize: 20, status: 'PAID', cursor: '1700000000000_42' })
  assert.deepEqual(buildOrderQuery({ status: '', cursor: '', pageSize: 20 }), { pageSize: 20 })
})

test('追加分页不重复覆盖已有订单', () => {
  const first = mergeOrderPage(null, { orders: [{ id: '1' }, { id: '2' }], page: { total: 3, hasMore: true, nextCursor: 'c1' } })
  assert.equal(first.orders.length, 2); assert.equal(first.total, 3); assert.equal(first.hasMore, true); assert.equal(first.nextCursor, 'c1')
  const second = mergeOrderPage(first, { orders: [{ id: '3' }], page: { total: 3, hasMore: false, nextCursor: null } })
  assert.deepEqual(second.orders.map(order => order.id), ['1', '2', '3'])
  assert.equal(second.hasMore, false); assert.equal(second.nextCursor, '')
})

test('切换筛选必须清空旧列表与旧游标', () => {
  const previous = mergeOrderPage(null, { orders: [{ id: 'old' }], page: { total: 1, hasMore: true, nextCursor: 'old-cursor' } })
  const reset = mergeOrderPage(previous, { orders: [{ id: 'new' }], page: { total: 1, hasMore: false, nextCursor: null } }, { reset: true })
  assert.deepEqual(reset.orders.map(order => order.id), ['new'])
  assert.equal(reset.nextCursor, '')
})

test('缺失分页元数据不伪造尾页，非法结构按空页处理', () => {
  assert.deepEqual(readOrderPage({ orders: [{ id: '1' }] }), { orders: [{ id: '1' }], total: 1, hasMore: false, nextCursor: '' })
  assert.deepEqual(readOrderPage(null), { orders: [], total: 0, hasMore: false, nextCursor: '' })
  assert.equal(orderStatusLabel('PAID'), '待发货')
  assert.equal(orderStatusLabel('UNKNOWN'), 'UNKNOWN')
})

test('T28 卡密详情迟到响应不得覆盖新订单弹窗', () => {
  const source = readFileSync(new URL('../shop-admin-vue/src/views/merchant/Orders.vue', import.meta.url), 'utf8')
  const fn = source.slice(source.indexOf('const viewOrderCards ='))
  assert.ok(fn.includes('const sequence = ++cardDetailSequence'))
  assert.ok(fn.includes('sequence !== cardDetailSequence'))
  assert.ok(fn.indexOf('cardDetailItems.value = []') < fn.indexOf('request.get('), '打开弹窗先清空旧卡密')
  assert.ok(source.includes('@closed="invalidateCardDetailLoad"'), '关闭弹窗必须作废未完成请求')
})

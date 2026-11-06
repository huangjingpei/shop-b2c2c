/*
 * 文件：merchantProducts.test.mjs
 * 用途：商家商品接口 {product, skus} 契约的共享回归用例，确保商品列表/统计/卡密导入页不再把包装行当商品。
 * 维护注意：仅使用内存样例，不访问后端；真实接口字段顺序与租户隔离由后端集成测试和人工联调验证。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { merchantProductRows, skusForMerchantProduct } from '../shop-admin-vue/src/utils/merchantProducts.mjs'

const rows = [
  { product: { id: 1, title: '实物', goodsType: 1 }, skus: [{ id: 11, price: 10 }] },
  { product: { id: 2, title: '虚拟', goodsType: 2 }, skus: [{ id: 21, price: 20 }] }
]

test('包装行正确展开为商品并保留顺序', () => {
  assert.deepEqual(merchantProductRows(rows).map(p => p.id), [1, 2])
})

test('缺失product或无效编号的行被跳过', () => {
  const dirty = [null, {}, { product: null }, { product: { title: '无编号' } }, ...rows]
  assert.deepEqual(merchantProductRows(dirty).map(p => p.id), [1, 2])
})

test('非数组响应返回空列表而不是抛错', () => {
  for (const value of [null, undefined, '', {}, 1]) {
    assert.deepEqual(merchantProductRows(value), [])
  }
})

test('按商品编号取SKU，未知编号返回空', () => {
  assert.deepEqual(skusForMerchantProduct(rows, 2).map(s => s.id), [21])
  assert.deepEqual(skusForMerchantProduct(rows, 999), [])
  assert.deepEqual(skusForMerchantProduct(null, 1), [])
})
test('T37 大整数商品编号按字符串匹配，不因Number失真', () => {
  const large = [{ product: { id: '9007199254740993' }, skus: [{ id: '9007199254740995' }] }]
  assert.deepEqual(skusForMerchantProduct(large, '9007199254740993').map(s => s.id), ['9007199254740995'])
  assert.deepEqual(skusForMerchantProduct(large, '9007199254740992'), [])
})

test('商品列表页只展示goodsType=2的虚拟商品', () => {
  assert.deepEqual(merchantProductRows(rows).filter(p => p.goodsType === 2).map(p => p.id), [2])
})

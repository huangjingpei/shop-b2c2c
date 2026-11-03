/*
 * 文件：productEditing.test.mjs
 * 用途：商品编辑表单独立性、请求字段白名单、售价边界及商家上下架规则测试。
 * 维护注意：纯Node测试，不模拟服务端授权；后端另测事务回滚、并发冲突与租户归属。库存不能因浏览器快照陈旧而被写回。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { editorForm, updatePayload, statusAction, productStatusLabel } from '../shop-admin-vue/src/utils/productEditing.mjs'
function snapshot() {
  return { revision: 'revision', product: { id: 1, merchantId: 2, categoryId: 1, title: '商品', mainImage: 'image', goodsType: 2, status: 1 },
    skus: [{ id: 1, skuCode: 'code', specData: '{}', price: 10, stockQuantity: 9 }] }
}
test('编辑表单与服务端快照互不覆盖，空可选字段可编辑', () => {
  const source = snapshot(), form = editorForm(source)
  form.skus[0].price = 20; form.title = '新标题'
  assert.equal(source.skus[0].price, 10); assert.equal(source.product.title, '商品')
  assert.equal(form.subTitle, ''); assert.equal(form.serviceDesc, '')
})
test('保存请求只发送资料与售价，不能夹带库存、归属、类型或状态', () => {
  const form = { ...editorForm(snapshot()), merchantId: 999, goodsType: 1, status: 1, minPrice: 1 }
  const payload = updatePayload(form)
  assert.deepEqual(payload.skus, [{ id: 1, price: 10 }])
  assert.deepEqual(Object.keys(payload).sort(), ['revision', 'categoryId', 'title', 'subTitle', 'mainImage', 'serviceDesc', 'skus'].sort())
})
for (const price of [0, -1, 0.001, 100000000, NaN, Infinity, null, '']) test(`非法售价被拒绝：${String(price)}`, () => {
  const form = editorForm(snapshot()); form.skus[0].price = price
  assert.throws(() => updatePayload(form))
})
for (const price of [0.01, 19.9, 99999999.99]) test(`合法售价：${price}`, () => {
  const form = editorForm(snapshot()); form.skus[0].price = price
  assert.equal(updatePayload(form).skus[0].price, price)
})
test('缺失必填项与重复SKU不可提交', () => {
  for (const field of ['title', 'mainImage', 'revision', 'categoryId']) {
    const form = editorForm(snapshot()); form[field] = ''
    assert.throws(() => updatePayload(form))
  }
  const form = editorForm(snapshot()); form.skus.push({ ...form.skus[0] })
  assert.throws(() => updatePayload(form))
})
test('违规与未知状态没有商家自助上架动作', () => {
  assert.deepEqual(statusAction({ status: 1 }), { label: '下架', target: 0 })
  assert.deepEqual(statusAction({ status: 0 }), { label: '上架', target: 1 })
  assert.equal(statusAction({ status: 2 }), null); assert.equal(statusAction({ status: 9 }), null)
  assert.equal(productStatusLabel(2), '违规下架'); assert.equal(productStatusLabel(9), '未知状态')
})

/*
 * 文件：productEditing.test.mjs
 * 用途：商品发布初始库存、编辑表单独立性、字段白名单、售价、上下架及SKU增删规则测试。
 * 维护注意：纯Node测试，不模拟服务端授权；后端另测事务回滚、并发冲突与租户归属。库存不能因浏览器快照陈旧而被写回。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { editorForm, updatePayload, statusAction, productStatusLabel, productCreationPayload, skuCreatePayload, skuSpecPayload, canRemoveSku, parseDetailImages, detailImagesPayload, DETAIL_ITEM_MAX, DETAIL_URL_MAX, DETAIL_TEXT_MAX } from '../shop-admin-vue/src/utils/productEditing.mjs'
function snapshot() {
  return { revision: 'revision', product: { id: 1, merchantId: 2, categoryId: 1, title: '商品', mainImage: 'image', goodsType: 2, status: 1 },
    skus: [{ id: 1, skuCode: 'code', specData: '{}', price: 10, stockQuantity: 9 }] }
}
test('自动卡密发布库存为0，保留原表单且请求SKU快照独立', () => {
  const form = { goodsType: 2, skus: [{ stockQuantity: 10 }] }
  const payload = productCreationPayload(form)
  assert.equal(payload.skus[0].stockQuantity, 0); assert.equal(form.skus[0].stockQuantity, 10)
  payload.skus[0].stockQuantity = 99; assert.equal(form.skus[0].stockQuantity, 10)
})
test('实物和定制服务发布保留初始可售数量', () => {
  for (const goodsType of [1, 3]) assert.equal(productCreationPayload({ goodsType, skus: [{ stockQuantity: 10 }] }).skus[0].stockQuantity, 10)
})
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
  assert.deepEqual(Object.keys(payload).sort(), ['revision', 'categoryId', 'title', 'subTitle', 'mainImage', 'serviceDesc', 'detailImages', 'skus'].sort())
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
test('新增SKU请求自动卡密强制零库存且不带服务端字段', () => {
  assert.deepEqual(skuCreatePayload(2, { skuCode: ' code-2 ', specData: '{"规格":"季卡"}', price: 5, stockQuantity: 9 }),
    { skuCode: 'code-2', specData: '{"规格":"季卡"}', price: 5, stockQuantity: 0 })
  assert.deepEqual(skuCreatePayload(1, { skuCode: 'c', specData: '{}', price: 0.01, stockQuantity: 3 }).stockQuantity, 3)
  assert.equal(skuCreatePayload(3, { skuCode: 'c', specData: '{}' , price: 1 }).stockQuantity, 0)
})
test('新增SKU非法编码、规格、售价与库存被拒绝', () => {
  for (const draft of [
    { skuCode: ' ', specData: '{}', price: 1, stockQuantity: 0 },
    { skuCode: 'x'.repeat(65), specData: '{}', price: 1, stockQuantity: 0 },
    { skuCode: 'c', specData: '', price: 1, stockQuantity: 0 },
    { skuCode: 'c', specData: '[1]', price: 1, stockQuantity: 0 },
    { skuCode: 'c', specData: 'not-json', price: 1, stockQuantity: 0 },
    { skuCode: 'c', specData: '{}', price: 0, stockQuantity: 0 },
    { skuCode: 'c', specData: '{}', price: 1.001, stockQuantity: 0 },
    { skuCode: 'c', specData: '{}', price: 1, stockQuantity: -1 },
    { skuCode: 'c', specData: '{}', price: 1, stockQuantity: 1.5 },
  ]) assert.throws(() => skuCreatePayload(1, draft), JSON.stringify(draft))
})
test('SKU删除按钮仅限零库存且非最后一个', () => {
  assert.equal(canRemoveSku({ stockQuantity: 0 }, 2), true)
  assert.equal(canRemoveSku({ stockQuantity: '0' }, 3), true)
  assert.equal(canRemoveSku({ stockQuantity: 1 }, 2), false)
  assert.equal(canRemoveSku({ stockQuantity: 0 }, 1), false)
  assert.equal(canRemoveSku(null, 2), false)
})
test('规格维护请求仅接受JSON对象且不超过255字符', () => {
  assert.deepEqual(skuSpecPayload(' {"a":1} '), { specData: '{"a":1}' })
  assert.deepEqual(skuSpecPayload('{}'), { specData: '{}' })
  for (const bad of ['', ' ', null, undefined, '[1]', 'not-json', 'x'.repeat(256), '"str"', '123'])
    assert.throws(() => skuSpecPayload(bad), String(bad))
})

test('详情图文解析容错：非法/空返回空数组，白名单字段抽取', () => {
  assert.deepEqual(parseDetailImages(null), [])
  assert.deepEqual(parseDetailImages(''), [])
  assert.deepEqual(parseDetailImages('not-json'), [])
  assert.deepEqual(parseDetailImages('{"a":1}'), [])
  assert.deepEqual(parseDetailImages('[1,"x",null]'), [])
  assert.deepEqual(
    parseDetailImages('[{"type":"image","url":"/a.jpg","extra":1},{"type":"text","content":"段落"},{"type":"video","url":"/v.mp4"}]'),
    [{ type: 'image', url: '/a.jpg', content: '' }, { type: 'text', url: '', content: '段落' }])
})

test('编辑表单从服务端JSON恢复详情图文，空值安全', () => {
  const withDetail = snapshot()
  withDetail.product.detailImages = '[{"type":"image","url":"/a.jpg"}]'
  assert.deepEqual(editorForm(withDetail).detailImages, [{ type: 'image', url: '/a.jpg', content: '' }])
  assert.deepEqual(editorForm(snapshot()).detailImages, [])
})

test('详情图文提交校验：trim、长度与协议白名单，空数组清空为null', () => {
  assert.equal(detailImagesPayload([]), null)
  assert.equal(detailImagesPayload(undefined), null)
  assert.deepEqual(detailImagesPayload([
    { type: 'image', url: '  https://cdn.example.com/a.jpg  ' },
    { type: 'text', content: '  支持验货  ' }]),
    [{ type: 'image', url: 'https://cdn.example.com/a.jpg' }, { type: 'text', content: '支持验货' }])
  assert.equal(detailImagesPayload(Array(DETAIL_ITEM_MAX).fill({ type: 'text', content: 'x' })).length, DETAIL_ITEM_MAX)
  assert.equal(detailImagesPayload([{ type: 'image', url: '/' + 'x'.repeat(DETAIL_URL_MAX - 1) }])[0].url.length, DETAIL_URL_MAX)
  assert.equal(detailImagesPayload([{ type: 'text', content: 'y'.repeat(DETAIL_TEXT_MAX) }])[0].content.length, DETAIL_TEXT_MAX)
})

test('详情图文非法条目被拒绝', () => {
  const bad = [
    [{ type: 'video', url: '/v.mp4' }],
    [{ type: 'image', url: '' }],
    [{ type: 'image', url: '   ' }],
    [{ type: 'image', url: 'ftp://a.jpg' }],
    [{ type: 'image', url: 'x'.repeat(DETAIL_URL_MAX + 1) }],
    [{ type: 'text', content: '' }],
    [{ type: 'text', content: '  ' }],
    [{ type: 'text', content: 'y'.repeat(DETAIL_TEXT_MAX + 1) }],
    [null],
    [{ url: '/a.jpg' }],
    Array(DETAIL_ITEM_MAX + 1).fill({ type: 'text', content: 'x' }),
  ]
  for (const items of bad) assert.throws(() => detailImagesPayload(items), JSON.stringify(items))
  // 保存请求同样拦截
  const form = editorForm(snapshot())
  form.detailImages = [{ type: 'image', url: 'javascript:alert(1)' }]
  assert.throws(() => updatePayload(form))
})

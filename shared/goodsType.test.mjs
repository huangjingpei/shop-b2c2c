/*
 * 文件：goodsType.test.mjs
 * 用途：用同一组Node单元测试验证两端各自的商品类型工具。
 * 维护注意：两端保留独立可打包模块，防止部署遗漏跨工程源码。标签、人工履约权限与凭证等待规则变更应同时测试两套实现；在根目录用node --test shared/goodsType.test.mjs运行。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import * as admin from '../shop-admin-vue/src/utils/goodsType.mjs'
import * as storefront from '../shop-storefront-vue/src/utils/goodsType.mjs'
for (const { goodsTypeLabel, canFulfillService, serviceVoucherText } of [admin, storefront]) {
for (const [type, label] of [[1,'实物商品'],[2,'自动卡密'],[3,'定制服务'],[null,'未知类型'],[4,'未知类型']]) {
  test(`商品类型 ${type} 展示 ${label}`, () => assert.equal(goodsTypeLabel(type), label))
}

for (const [type, status, allowed] of [[1,'PAID',false],[2,'PAID',false],[3,'PAID',true],[3,'UNPAID',false],[3,'COMPLETED',false]]) {
  test(`手工履约 ${type}/${status}`, () => assert.equal(canFulfillService(type,status), allowed))
}
test('服务未交付时显示等待状态', () => assert.equal(serviceVoucherText(null), '尚未交付，请等待商家处理'))
test('服务已交付时显示实际凭证', () => assert.equal(serviceVoucherText('result'), 'result'))

}

/*
 * 文件：productModeration.test.mjs
 * 用途：F11 平台商品违规处置前端纯规则测试：下架/恢复请求体装配、操作可用性与动作文案。
 * 维护注意：纯Node测试，不模拟服务端授权；后端另测事务、CAS并发冲突与角色校验。长度口径须与后端
 * ProductModerationService 保持一致（reason 1..255 / note ≤255）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { takedownPayload, restorePayload, canTakedown, canRestore, moderationActionLabel, MODERATION_REASON_MAX } from '../shop-admin-vue/src/utils/productModeration.mjs'

test('违规下架原因必填并 trim 后回传', () => {
  assert.deepEqual(takedownPayload({ reason: '  涉嫌侵权  ' }), { reason: '涉嫌侵权' })
  for (const bad of ['', '   ', null, undefined])
    assert.throws(() => takedownPayload({ reason: bad }), String(bad))
  assert.throws(() => takedownPayload({}), '缺字段')
})

test('违规下架原因最长 255 个字符，边界通过', () => {
  assert.equal(takedownPayload({ reason: 'x'.repeat(MODERATION_REASON_MAX) }).reason.length, MODERATION_REASON_MAX)
  assert.throws(() => takedownPayload({ reason: 'x'.repeat(MODERATION_REASON_MAX + 1) }))
})

test('恢复备注可选，空值提交空对象，有值 trim 后回传', () => {
  assert.deepEqual(restorePayload({ note: '' }), {})
  assert.deepEqual(restorePayload({ note: '   ' }), {})
  assert.deepEqual(restorePayload({}), {})
  assert.deepEqual(restorePayload({ note: '  已整改  ' }), { note: '已整改' })
})

test('恢复备注最长 255 个字符，边界通过', () => {
  assert.equal(restorePayload({ note: 'y'.repeat(MODERATION_REASON_MAX) }).note.length, MODERATION_REASON_MAX)
  assert.throws(() => restorePayload({ note: 'y'.repeat(MODERATION_REASON_MAX + 1) }))
})

test('违规下架仅允许已下架或在售商品发起', () => {
  assert.equal(canTakedown(0), true)
  assert.equal(canTakedown(1), true)
  assert.equal(canTakedown(2), false)
  assert.equal(canTakedown(9), false)
  assert.equal(canTakedown(undefined), false)
})

test('审核恢复仅允许违规下架商品', () => {
  assert.equal(canRestore(2), true)
  assert.equal(canRestore(0), false)
  assert.equal(canRestore(1), false)
  assert.equal(canRestore(9), false)
})

test('处置动作文案映射与未知兜底', () => {
  assert.equal(moderationActionLabel('TAKEDOWN'), '违规下架')
  assert.equal(moderationActionLabel('RESTORE'), '审核恢复')
  assert.equal(moderationActionLabel('OTHER'), '未知动作')
  assert.equal(moderationActionLabel(undefined), '未知动作')
})

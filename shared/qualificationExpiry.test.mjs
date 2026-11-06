/*
 * 文件：qualificationExpiry.test.mjs
 * 用途：O9 资质有效期前端规则回归：日期严格解析、天数差、状态口径（60 天临近/到期当天即 EXPIRED）、
 *       展示视图文案、更新表单校验（至少一项/格式/未来日期）。
 * 维护注意：只测纯函数；today 注入固定值保证可重复，不依赖真实时钟。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  EXPIRING_DAYS,
  parseExpiry,
  daysUntil,
  qualificationStatus,
  qualificationView,
  validateExpiryUpdate
} from '../shop-admin-vue/src/utils/qualificationExpiry.mjs'

const TODAY = '2026-11-07'

test('parseExpiry 严格校验格式与真实日期', () => {
  assert.ok(parseExpiry('2026-11-07') !== null)
  assert.equal(parseExpiry('2026-02-30'), null)
  assert.equal(parseExpiry('2026-1-7'), null)
  assert.equal(parseExpiry('2026/11/07'), null)
  assert.equal(parseExpiry(''), null)
  assert.equal(parseExpiry(null), null)
  assert.equal(parseExpiry(' 2026-11-07 '), parseExpiry('2026-11-07'))
})

test('daysUntil 正数为未到期、0 为当天、负数为已过期', () => {
  assert.equal(daysUntil('2026-11-07', TODAY), 0)
  assert.equal(daysUntil('2026-11-08', TODAY), 1)
  assert.equal(daysUntil('2026-11-06', TODAY), -1)
  assert.equal(daysUntil('2027-01-06', TODAY), 60)
  assert.equal(daysUntil('bad', TODAY), null)
})

test('qualificationStatus 口径：空为 NONE，到期当天即 EXPIRED，60 天内 EXPIRING', () => {
  assert.equal(qualificationStatus(null, TODAY), 'NONE')
  assert.equal(qualificationStatus('', TODAY), 'NONE')
  assert.equal(qualificationStatus('2026-11-07', TODAY), 'EXPIRED')
  assert.equal(qualificationStatus('2026-11-06', TODAY), 'EXPIRED')
  assert.equal(qualificationStatus('2026-11-08', TODAY), 'EXPIRING')
  assert.equal(qualificationStatus('2027-01-06', TODAY), 'EXPIRING')
  assert.equal(qualificationStatus('2027-01-07', TODAY), 'VALID')
  assert.equal(EXPIRING_DAYS, 60)
})

test('qualificationView 展示文案与标签级别', () => {
  const expired = qualificationView('2026-10-01', '身份证', TODAY)
  assert.equal(expired.status, 'EXPIRED')
  assert.equal(expired.tagType, 'danger')
  assert.ok(expired.text.includes('已过期 37 天'))
  assert.ok(expired.text.includes('限制发布新商品'))

  const dueToday = qualificationView('2026-11-07', '身份证', TODAY)
  assert.ok(dueToday.text.includes('今天到期'))

  const expiring = qualificationView('2026-12-01', '营业执照', TODAY)
  assert.equal(expiring.tagType, 'warning')
  assert.ok(expiring.text.includes('24 天后到期'))

  const none = qualificationView('', '营业执照', TODAY)
  assert.equal(none.status, 'NONE')
  assert.equal(none.tagType, 'info')
})

test('validateExpiryUpdate 至少一项、格式合法且必须未来日期', () => {
  assert.equal(validateExpiryUpdate('', '', TODAY).valid, false)
  assert.equal(validateExpiryUpdate('', null, TODAY).valid, false)

  const past = validateExpiryUpdate('2026-11-07', '', TODAY)
  assert.equal(past.valid, false)
  assert.ok(past.message.includes('未来日期'))

  const badFormat = validateExpiryUpdate('2026-02-30', '', TODAY)
  assert.equal(badFormat.valid, false)
  assert.ok(badFormat.message.includes('YYYY-MM-DD'))

  const ok = validateExpiryUpdate('2030-12-31', '', TODAY)
  assert.equal(ok.valid, true)
  assert.equal(ok.idCardExpiry, '2030-12-31')
  assert.equal(ok.licenseExpiry, '')

  const both = validateExpiryUpdate(' 2030-12-31 ', '2035-06-30', TODAY)
  assert.equal(both.valid, true)
  assert.equal(both.idCardExpiry, '2030-12-31')
})

/*
 * 文件：onboardingReview.test.mjs
 * 用途：O7 开店审核工作台前端的共享规则回归：费率口径（0 推广必填原因、100–1000bps）、原因码字典与校验、
 *      请求体字段契约、审核 SLA 超时标记、复核阶段文案。
 * 维护注意：只测纯函数，不调用接口；动作合法性、双人复核与费率范围最终以后端用例为准；
 *      原因码字典变更必须与后端 REJECT_CODES 同步，本用例会失败提示。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  REJECT_CODES,
  REJECT_CODE_VALUES,
  REVIEW_SLA_HOURS,
  buildReviewPayload,
  isSlaBreach,
  reviewStageText,
  validateRateBps,
  validateRejectPayload
} from '../shop-admin-vue/src/utils/onboardingReview.mjs'

test('原因码字典覆盖后端 15 码且 OTHER 在列', () => {
  assert.equal(REJECT_CODES.length, 15)
  for (const expected of [
    'SUBJECT_INFO_MISMATCH', 'NAME_ID_MISMATCH', 'FACE_VERIFY_FAILED',
    'ID_CARD_INVALID', 'ID_CARD_EXPIRED', 'LICENSE_INVALID', 'LICENSE_EXPIRED',
    'LICENSE_SUBJECT_MISMATCH', 'STORE_NAME_VIOLATION', 'MATERIAL_UNCLEAR',
    'MATERIAL_MISSING', 'DUPLICATE_SUBJECT', 'CATEGORY_NOT_SUPPORTED',
    'RISK_CONTROL', 'OTHER'
  ]) {
    assert.ok(REJECT_CODE_VALUES.includes(expected), `缺少原因码 ${expected}`)
  }
  assert.ok(REJECT_CODES.every(item => item.label && item.label.length > 0))
})

test('费率：留空走平台默认，0 为推广必须填写推广原因', () => {
  assert.deepEqual(validateRateBps(undefined, ''), { ok: true, message: '' })
  assert.deepEqual(validateRateBps('', null), { ok: true, message: '' })
  assert.equal(validateRateBps(0, '').ok, false)
  assert.match(validateRateBps(0, '').message, /推广原因/)
  assert.equal(validateRateBps(0, '  新店推广期免佣  ').ok, true)
})

test('费率：仅支持 0 或 100–1000，拒绝中间值/超界/非整数', () => {
  assert.equal(validateRateBps(100, '').ok, true)
  assert.equal(validateRateBps(1000, '').ok, true)
  assert.equal(validateRateBps(99, '').ok, false)
  assert.equal(validateRateBps(1001, '').ok, false)
  assert.equal(validateRateBps(50, '').ok, false)
  assert.equal(validateRateBps(300.5, '').ok, false)
  assert.equal(validateRateBps(-100, '').ok, false)
})

test('驳回/补件必须选择合法原因码，OTHER 必须填写说明', () => {
  assert.equal(validateRejectPayload({ action: 'REJECT', rejectCode: '', reason: 'x' }).ok, false)
  assert.equal(validateRejectPayload({ action: 'REJECT', rejectCode: 'NOT_A_CODE', reason: 'x' }).ok, false)
  assert.equal(validateRejectPayload({ action: 'REJECT', rejectCode: 'OTHER', reason: '' }).ok, false)
  assert.equal(validateRejectPayload({ action: 'REJECT', rejectCode: 'OTHER', reason: '现场核验不通过' }).ok, true)
  assert.equal(validateRejectPayload({ action: 'SUPPLEMENT_REQUIRED', rejectCode: 'MATERIAL_MISSING', reason: '' }).ok, true)
  assert.equal(validateRejectPayload({ action: 'APPROVE', rejectCode: 'OTHER', reason: '' }).ok, false)
})

test('请求体契约：APPROVE 带费率与复核人，REJECT 带原因码，剔除空值', () => {
  const approve = buildReviewPayload({
    action: 'APPROVE',
    reason: '  材料齐全  ',
    storeName: ' 旗舰店 ',
    rateBps: 300,
    promotionReason: '',
    rateConfirmedBy: 2
  })
  assert.deepEqual(approve, {
    action: 'APPROVE', reason: '材料齐全', storeName: '旗舰店', rateBps: 300, rateConfirmedBy: 2
  })
  assert.ok(!('rejectCode' in approve))

  const zeroRate = buildReviewPayload({ action: 'APPROVE', rateBps: 0, promotionReason: '推广期' })
  assert.deepEqual(zeroRate, { action: 'APPROVE', rateBps: 0, promotionReason: '推广期' })

  const reject = buildReviewPayload({ action: 'REJECT', reason: '  ', rejectCode: 'OTHER' })
  assert.deepEqual(reject, { action: 'REJECT', rejectCode: 'OTHER' })
  assert.ok(!('rateBps' in reject) && !('reason' in reject))

  const supplement = buildReviewPayload({ action: 'SUPPLEMENT_REQUIRED', rejectCode: 'MATERIAL_MISSING', reason: '请补拍执照' })
  assert.deepEqual(supplement, { action: 'SUPPLEMENT_REQUIRED', reason: '请补拍执照', rejectCode: 'MATERIAL_MISSING' })

  const startReview = buildReviewPayload({ action: 'START_REVIEW', reason: '先领单' })
  assert.deepEqual(startReview, { action: 'START_REVIEW', reason: '先领单' })
})

test('SLA 超时：仅待审状态按提交时间 48h 判定，终态与缺时间不标记', () => {
  const now = new Date('2026-11-07T12:00:00Z')
  const old = new Date(now.getTime() - (REVIEW_SLA_HOURS + 1) * 3600 * 1000).toISOString()
  const fresh = new Date(now.getTime() - 3600 * 1000).toISOString()
  assert.equal(isSlaBreach({ status: 'SUBMITTED', submittedAt: old }, now), true)
  assert.equal(isSlaBreach({ status: 'REVIEWING', submittedAt: old }, now), true)
  assert.equal(isSlaBreach({ status: 'SUBMITTED', submittedAt: fresh }, now), false)
  assert.equal(isSlaBreach({ status: 'APPROVED', submittedAt: old }, now), false)
  assert.equal(isSlaBreach({ status: 'REJECTED', submittedAt: old }, now), false)
  assert.equal(isSlaBreach({ status: 'SUBMITTED', submittedAt: null }, now), false)
  assert.equal(isSlaBreach({ status: 'SUBMITTED', submittedAt: 'not-a-date' }, now), false)
  assert.equal(isSlaBreach(null, now), false)
})

test('复核阶段文案覆盖后端取值', () => {
  assert.equal(reviewStageText('NONE'), '未进入审核')
  assert.equal(reviewStageText('PRIMARY'), '初审中')
  assert.equal(reviewStageText('SECONDARY'), '待复审')
  assert.equal(reviewStageText('PASSED'), '终审通过')
  assert.equal(reviewStageText(undefined), '-')
})

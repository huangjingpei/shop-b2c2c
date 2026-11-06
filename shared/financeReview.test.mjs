/*
 * 文件：financeReview.test.mjs
 * 用途：G31 复核人前端规则回归：严格模式必填、禁用自身、无第二账号明确提示、宽松模式同人放行。
 * 维护注意：只测纯函数，不调用接口；最终权限与落库口径仍以后端 FinanceApprovalGuard 用例为准。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  NO_SECOND_ACCOUNT_MESSAGE,
  REVIEWER_ERROR,
  reviewerOptions,
  reviewerState,
  validateReviewer
} from '../shop-admin-vue/src/utils/financeReview.mjs'

const reviewers = [
  { id: 1, username: 'admin', realName: '超管A' },
  { id: '2', username: 'finance', realName: '财务B' },
  { id: null, username: 'broken', realName: '无效账号' }
]

test('候选复核人排除当前操作人并兼容字符串/数字 ID', () => {
  assert.deepEqual(reviewerOptions(reviewers, 1).map(item => String(item.id)), ['2'])
  assert.deepEqual(reviewerOptions(reviewers, '2').map(item => String(item.id)), ['1'])
})

test('严格模式没有第二个账号时阻断并给出明确提示', () => {
  const state = reviewerState({ dualApprovalRequired: true, reviewers: [reviewers[0]], currentUserId: 1 })
  assert.equal(state.blocked, true)
  assert.equal(state.hasSecondAccount, false)
  assert.equal(state.notice, NO_SECOND_ACCOUNT_MESSAGE)

  const result = validateReviewer({
    dualApprovalRequired: true,
    reviewers: [reviewers[0]],
    currentUserId: 1,
    reviewerId: ''
  })
  assert.equal(result.ok, false)
  assert.equal(result.code, REVIEWER_ERROR.NO_SECOND_ACCOUNT)
  assert.equal(result.message, NO_SECOND_ACCOUNT_MESSAGE)
  // 不允许抛通用错误或静默放行。
  assert.notEqual(result.code, 'REVIEWER_REQUIRED')
})

test('严格模式有第二账号但未选择复核人时明确必填', () => {
  const result = validateReviewer({
    dualApprovalRequired: true,
    reviewers,
    currentUserId: 1,
    reviewerId: ''
  })
  assert.equal(result.ok, false)
  assert.equal(result.code, REVIEWER_ERROR.REVIEWER_REQUIRED)
  assert.match(result.message, /请选择复核人/)
})

test('前端拒绝把当前操作人作为复核人', () => {
  const result = validateReviewer({
    dualApprovalRequired: true,
    reviewers,
    currentUserId: 1,
    reviewerId: 1
  })
  assert.equal(result.ok, false)
  assert.equal(result.code, REVIEWER_ERROR.SELF_REVIEWER)
})

test('前端拒绝不在候选列表中的账号', () => {
  const result = validateReviewer({
    dualApprovalRequired: false,
    reviewers,
    currentUserId: 1,
    reviewerId: 99
  })
  assert.equal(result.ok, false)
  assert.equal(result.code, REVIEWER_ERROR.UNKNOWN_REVIEWER)
})

test('选择其他管理员时按双人复核提交 confirmedBy', () => {
  const result = validateReviewer({
    dualApprovalRequired: true,
    reviewers,
    currentUserId: 1,
    reviewerId: '2'
  })
  assert.equal(result.ok, true)
  assert.equal(result.code, 'DUAL_APPROVAL')
  assert.equal(result.confirmedBy, '2')
})

test('宽松模式未选复核人时按操作人处理并提示同人标记', () => {
  const result = validateReviewer({
    dualApprovalRequired: false,
    reviewers,
    currentUserId: 1,
    reviewerId: ''
  })
  assert.equal(result.ok, true)
  assert.equal(result.code, 'SAME_OPERATOR')
  assert.equal(result.confirmedBy, null)
  assert.match(result.message, /同人复核/)
})

test('宽松模式无第二账号仍允许提交但保留明确提示', () => {
  const state = reviewerState({ dualApprovalRequired: false, reviewers: [reviewers[0]], currentUserId: 1 })
  assert.equal(state.blocked, false)
  assert.equal(state.notice, NO_SECOND_ACCOUNT_MESSAGE)

  const result = validateReviewer({
    dualApprovalRequired: false,
    reviewers: [reviewers[0]],
    currentUserId: 1,
    reviewerId: ''
  })
  assert.equal(result.ok, true)
  assert.equal(result.code, 'SAME_OPERATOR')
  assert.equal(result.message, NO_SECOND_ACCOUNT_MESSAGE)
})

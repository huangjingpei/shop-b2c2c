import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  COMPLAINT_TYPES, COMPLAINT_STATUSES, isValidComplaintContent, isValidEvidenceList,
  complaintSubmitPayload, merchantReplyPayload, arbitratePayload,
  complaintStatusLabel, complaintStatusTag, canReplyComplaint, canEscalateComplaint, canArbitrateComplaint
} from '../shared/complaint.mjs'

test('投诉说明与举证链接校验', () => {
  assert.equal(isValidComplaintContent('收到商品破损'), true)
  assert.equal(isValidComplaintContent('坏'), false)
  assert.equal(isValidComplaintContent('x'.repeat(1001)), false)
  assert.equal(isValidEvidenceList(null), true)
  assert.equal(isValidEvidenceList([]), true)
  assert.equal(isValidEvidenceList(['https://cdn.example.com/a.jpg']), true)
  assert.equal(isValidEvidenceList(['javascript:alert(1)']), false)
  assert.equal(isValidEvidenceList(['https://cdn.example.com/a b.jpg']), false)
  assert.equal(isValidEvidenceList(new Array(6).fill('https://a.cn/x.jpg')), false)
})

test('提交 payload 必填与类型白名单', () => {
  const ok = complaintSubmitPayload({
    orderSn: 'O1', contact: '138', queryToken: 'tk', complaintType: 'QUALITY',
    content: '商品破损且无法使用', evidenceImages: ['https://cdn.example.com/a.jpg ']
  })
  assert.equal(ok.complaintType, 'QUALITY')
  assert.deepEqual(ok.evidenceImages, ['https://cdn.example.com/a.jpg'])
  assert.throws(() => complaintSubmitPayload({ orderSn: '', contact: '138', queryToken: 'tk', complaintType: 'QUALITY', content: '商品破损且无法使用' }))
  assert.throws(() => complaintSubmitPayload({ orderSn: 'O1', contact: '138', queryToken: 'tk', complaintType: 'HACK', content: '商品破损且无法使用' }))
})

test('商家答复与裁决 payload 边界', () => {
  assert.equal(merchantReplyPayload({ reply: '  已核实  ' }).reply, '已核实')
  assert.throws(() => merchantReplyPayload({ reply: '' }))
  assert.deepEqual(arbitratePayload({ decision: 'RESOLVE', arbitration: '支持退款', refundAmount: '20.5' }), {
    decision: 'RESOLVE', arbitration: '支持退款', refundAmount: '20.5'
  })
  assert.throws(() => arbitratePayload({ decision: 'REJECT', arbitration: '驳回', refundAmount: '10' }))
  assert.throws(() => arbitratePayload({ decision: 'RESOLVE', arbitration: '结论', refundAmount: '1.234' }))
  assert.throws(() => arbitratePayload({ decision: 'CLOSE', arbitration: '结论', refundAmount: '0' }))
})

test('状态与操作可见性', () => {
  assert.deepEqual(COMPLAINT_TYPES, ['QUALITY', 'FAKE', 'SERVICE_NOT_DELIVERED', 'OTHER'])
  assert.deepEqual(COMPLAINT_STATUSES, ['PENDING', 'MERCHANT_REPLIED', 'ARBITRATING', 'RESOLVED', 'REJECTED'])
  assert.equal(complaintStatusLabel('ARBITRATING'), '平台仲裁中')
  assert.equal(complaintStatusTag('REJECTED'), 'danger')
  assert.equal(canReplyComplaint('MERCHANT_REPLIED'), true)
  assert.equal(canReplyComplaint('ARBITRATING'), false)
  assert.equal(canEscalateComplaint('PENDING'), true)
  assert.equal(canArbitrateComplaint('RESOLVED'), false)
})

<!--
文件：QuickQuery.vue
用途：免登录订单查单及卡密、定制服务凭证和物流展示。
维护注意：类型3展示人工交付凭证；仅以文本插值渲染，不能直接v-html注入凭证。领取必须通过POST验证独立queryToken；不得把联系方式/凭证放URL、自动日志或持久化浏览器存储。
F06：查单结果内可提交维权投诉、查看商家答复与平台裁决、申请平台介入；举证仅接受http/https链接文本，仍由后端校验订单归属。
-->
<template>
  <div class="max-w-2xl mx-auto py-4">
    <!-- 标头 -->
    <div class="text-center mb-8">
      <span class="inline-block px-3 py-1 rounded-full bg-brand-100 text-brand-700 text-xs font-bold mb-2">
        ⚡ 16688 招牌特色功能
      </span>
      <h1 class="text-2xl font-black text-slate-900 tracking-tight">免登录极速查单与卡密提取</h1>
      <p class="text-xs text-slate-500 mt-1.5">
        输入订单号、下单联系方式和私密领取凭证，查询交付内容或物流。
      </p>
    </div>

    <!-- 查询表单卡片 -->
    <div class="bg-white rounded-xl border border-slate-200 p-6 shadow-sm mb-6">
      <form @submit.prevent="handleSearch" class="space-y-4">
        <div>
          <label class="block text-xs font-bold text-slate-700 mb-1">
            订单流水编号 (Order SN) <span class="text-red-500">*</span>
          </label>
          <input
            v-model="orderSn"
            type="text"
            placeholder="例如 ORD20261101..."
            class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-brand-500 font-mono"
            required
          />
        </div>

        <div>
          <label class="block text-xs font-bold text-slate-700 mb-1">
            下单时填写的手机号 / 邮箱 <span class="text-red-500">*</span>
          </label>
          <input
            v-model="contact"
            type="text"
            placeholder="请输入与订单绑定的联系方式"
            class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-brand-500"
            required
          />
        </div>

        <div>
          <label class="block text-xs font-bold text-slate-700 mb-1">买家领取凭证 *</label>
          <input v-model="queryToken" type="password" autocomplete="off" required maxlength="64"
            placeholder="下单时保存的领取凭证（不是代付分享码）"
            class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm" />
          <p class="text-xs text-slate-500 mt-1">请勿把领取凭证发给代付人或发到群聊。遗失后请联系店铺核实。</p>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-3 rounded-lg bg-brand-500 hover:bg-brand-600 disabled:bg-slate-300 text-white font-bold text-sm transition shadow-sm flex items-center justify-center space-x-2"
        >
          <span v-if="loading">正在验证领取凭证...</span>
          <span v-else>验证并查询订单</span>
        </button>
      </form>
    </div>

    <!-- 查询结果展示区 -->
    <div v-if="orderResult" class="bg-white rounded-xl border border-slate-200 p-6 shadow-sm">
      <div class="flex items-center justify-between pb-4 border-b border-slate-100">
        <div>
          <div class="text-xs text-slate-400">订单流水号</div>
          <div class="font-mono font-bold text-sm text-slate-900">{{ orderResult.order.orderSn }}</div>
        </div>
        <div class="text-right">
          <span
            class="px-2.5 py-1 rounded text-xs font-bold"
            :class="orderResult.order.orderStatus === 'COMPLETED' ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'"
          >
            {{ orderResult.order.orderStatus === 'COMPLETED' ? '✓ 订单已完成' : orderResult.order.orderStatus }}
          </span>
          <div class="text-[11px] text-slate-400 mt-1">下单时间: {{ orderResult.order.createdAt }}</div>
        </div>
      </div>

      <!-- F07 实物收货信息与确认收货 -->
      <div v-if="orderResult.order.goodsType === 1 && orderResult.order.receiverAddress"
           class="py-3 border-b border-slate-100 text-xs text-slate-600 space-y-1">
        <div class="font-bold text-slate-700">收货信息</div>
        <div>{{ orderResult.order.receiverName }} {{ orderResult.order.receiverPhone }}</div>
        <div>{{ orderResult.order.receiverRegion }} {{ orderResult.order.receiverAddress }}</div>
      </div>
      <div v-if="canConfirm(orderResult.order.orderStatus)" class="py-3 border-b border-slate-100 flex items-center justify-between">
        <div class="text-xs text-slate-600">收到货后请确认，系统也会在发货超期后自动完成。</div>
        <button @click="handleConfirmReceipt" :disabled="confirmingReceipt"
                class="px-3 py-1.5 rounded bg-green-50 text-green-700 text-xs font-bold hover:bg-green-100 disabled:opacity-50">
          {{ confirmingReceipt ? '提交中...' : '确认收货' }}
        </button>
      </div>
      <div v-if="orderResult.order.afterSaleDeadline" class="py-2 border-b border-slate-100 text-[11px] text-slate-500">
        售后受理截止：{{ orderResult.order.afterSaleDeadline }}
      </div>

      <!-- 退款申请入口：仅已支付/已发货/已完成可申请 -->
      <div v-if="canRefund" class="py-3 border-b border-slate-100 flex items-center justify-between">
        <div class="text-xs text-slate-600">实付 ￥{{ orderResult.order.payAmount }}，可申请退款</div>
        <button @click="openRefund" class="px-3 py-1.5 rounded bg-red-50 text-red-600 text-xs font-bold hover:bg-red-100">
          申请退款
        </button>
      </div>

      <!-- 退款申请记录 -->
      <div v-if="refundRequests.length" class="py-3 border-b border-slate-100">
        <div class="text-xs font-bold text-slate-700 mb-2">退款申请记录</div>
        <div v-for="rr in refundRequests" :key="rr.id" class="text-xs py-1.5 flex justify-between border-b border-slate-50 last:border-0">
          <span>￥{{ rr.refundAmount }} · {{ rr.reason }}</span>
          <span :class="refundStatusClass(rr.status)">{{ refundStatusLabel(rr.status) }}</span>
        </div>
      </div>

      <!-- 投诉与平台仲裁入口 -->
      <div v-if="canRefund" class="py-3 border-b border-slate-100">
        <div class="flex items-center justify-between mb-2">
          <div class="text-xs font-bold text-slate-700">维权投诉</div>
          <button v-if="!activeComplaint" @click="openComplaint"
                  class="px-3 py-1.5 rounded bg-slate-100 text-slate-700 text-xs font-bold hover:bg-slate-200">
            提交投诉
          </button>
        </div>
        <div v-for="c in complaints" :key="c.id" class="text-xs py-2 border-b border-slate-50 last:border-0 space-y-1">
          <div class="flex justify-between">
            <span class="font-bold">{{ complaintTypeLabel(c.complaintType) }}：{{ c.content }}</span>
            <span :class="complaintStatusClass(c.status)">{{ complaintStatusLabel(c.status) }}</span>
          </div>
          <div v-if="c.merchantReply" class="text-slate-500">商家答复：{{ c.merchantReply }}</div>
          <div v-if="c.adminArbitration" class="text-blue-700">
            平台裁决：{{ c.adminArbitration }}（退款 ￥{{ c.refundAmount }}）
          </div>
          <button v-if="canEscalateComplaint(c.status)" @click="escalateComplaint(c)"
                  class="px-2 py-1 rounded bg-amber-50 text-amber-700 text-[11px] font-bold hover:bg-amber-100">
            商家未解决？申请平台介入
          </button>
        </div>
      </div>

      <!-- 所属店铺 -->
      <div v-if="orderResult.store" class="py-3 flex items-center justify-between text-xs text-slate-600 border-b border-slate-100">
        <span>出货店铺：<strong class="text-slate-800">{{ orderResult.store.storeName }}</strong></span>
        <span>客服电话：{{ orderResult.store.contactPhone || '请联系店铺' }}</span>
      </div>

      <!-- 购买商品项 -->
      <div class="py-4 space-y-4">
        <div v-for="item in orderResult.items" :key="item.id" class="p-3 bg-slate-50 rounded-lg">
          <div class="flex items-center justify-between text-xs">
            <span class="font-bold text-slate-800">{{ item.productTitle }}</span>
            <span class="text-brand-600 font-bold">￥{{ item.subtotal }} (数量: {{ item.quantity }})</span>
          </div>
          <div class="text-[11px] text-slate-500 mt-0.5">{{ item.specInfo }}</div>

          <!-- 虚拟卡密交付内容快照 -->
          <div v-if="item.cardDetails" class="mt-3 pt-3 border-t border-slate-200">
            <div class="flex items-center justify-between text-xs font-bold text-amber-800 mb-1">
              <span>⚡ 提取到的虚拟卡密：</span>
              <button @click="copyText(item.cardDetails)" class="text-brand-600 hover:underline">点击复制</button>
            </div>
            <div class="p-2.5 bg-amber-50 rounded font-mono text-xs text-amber-900 whitespace-pre-wrap select-all border border-amber-200/60">
              {{ item.cardDetails }}
            </div>
          </div>
        </div>
      </div>

      <div v-if="orderResult.order.goodsType === 3" class="p-3 bg-blue-50 rounded-lg text-xs">
        <div class="font-bold">定制服务交付凭证</div>
        <pre class="whitespace-pre-wrap">{{ serviceVoucherText(orderResult.order.serviceVoucher) }}</pre>
      </div>
      <!-- 实物物流追踪信息 -->
      <div v-if="orderResult.order.goodsType === 1" class="mt-2 p-3 bg-blue-50 rounded-lg border border-blue-100 text-xs">
        <div class="font-bold text-blue-900 mb-1">📦 实物包裹物流信息</div>
        <div v-if="orderResult.order.trackingNumber" class="text-blue-800">
          快递公司：<strong>{{ orderResult.order.logisticsCompany }}</strong><br />
          运单号码：<strong class="font-mono">{{ orderResult.order.trackingNumber }}</strong><br />
          发货时间：{{ orderResult.order.deliveryTime }}
        </div>
        <div v-else class="text-blue-600">
          商家正在配货中，运单号生成后将在此处实时同步。
        </div>
      </div>
    </div>

    <!-- 退款申请对话框 -->
    <div v-if="refundDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" @click.self="refundDialog = false">
      <div class="bg-white rounded-xl p-5 w-full max-w-sm shadow-lg">
        <h3 class="font-bold text-slate-900 mb-3">申请退款</h3>
        <div class="text-xs text-slate-500 mb-3">订单 {{ orderResult?.order.orderSn }}，实付 ￥{{ orderResult?.order.payAmount }}</div>
        <label class="block text-xs font-bold text-slate-700 mb-1">退款金额</label>
        <input v-model.number="refundForm.amount" type="number" step="0.01" min="0.01"
               :max="orderResult?.order.payAmount"
               class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm mb-3" />
        <label class="block text-xs font-bold text-slate-700 mb-1">退款原因</label>
        <textarea v-model="refundForm.reason" rows="3" maxlength="256"
                  class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm mb-4"
                  placeholder="请说明退款原因"></textarea>
        <div class="flex justify-end gap-2">
          <button @click="refundDialog = false" class="px-4 py-2 rounded border border-slate-300 text-sm">取消</button>
          <button @click="submitRefund" :disabled="refunding"
                  class="px-4 py-2 rounded bg-red-500 text-white text-sm font-bold disabled:bg-slate-300">
            {{ refunding ? '提交中...' : '提交申请' }}
          </button>
        </div>
      </div>
    </div>
    <!-- 投诉提交对话框 -->
    <div v-if="complaintDialog" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" @click.self="complaintDialog = false">
      <div class="bg-white rounded-xl p-5 w-full max-w-sm shadow-lg">
        <h3 class="font-bold text-slate-900 mb-3">提交维权投诉</h3>
        <div class="text-xs text-slate-500 mb-3">订单 {{ orderResult?.order.orderSn }}</div>
        <label class="block text-xs font-bold text-slate-700 mb-1">投诉类型</label>
        <select v-model="complaintType" class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm mb-3">
          <option value="QUALITY">质量问题</option>
          <option value="FAKE">假冒商品</option>
          <option value="SERVICE_NOT_DELIVERED">未按约履约</option>
          <option value="OTHER">其他</option>
        </select>
        <label class="block text-xs font-bold text-slate-700 mb-1">投诉说明</label>
        <textarea v-model="complaintContent" rows="3" maxlength="1000"
                  class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm mb-3"
                  placeholder="请描述问题，至少 5 个字"></textarea>
        <label class="block text-xs font-bold text-slate-700 mb-1">举证图片链接（每行一条，最多 5 条）</label>
        <textarea v-model="complaintEvidence" rows="2"
                  class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm mb-4"
                  placeholder="https://..."></textarea>
        <div class="flex justify-end gap-2">
          <button @click="complaintDialog = false" class="px-4 py-2 rounded border border-slate-300 text-sm">取消</button>
          <button @click="submitComplaint" :disabled="complaintSubmitting"
                  class="px-4 py-2 rounded bg-amber-500 text-white text-sm font-bold disabled:bg-slate-300">
            {{ complaintSubmitting ? '提交中...' : '提交投诉' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { receiptRequest, receiptRouteQuery } from '../utils/buyerReceipt.mjs'
import { serviceVoucherText } from '../utils/goodsType.mjs'
import { complaintStatusLabel, complaintTypeLabel, canEscalateComplaint, complaintSubmitPayload } from '@shared/complaint.mjs'
import { canConfirmReceipt } from '@shared/orderReceipt.mjs'
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const orderSn = ref('')
const contact = ref('')
const queryToken = ref('')
const orderResult = ref(null)
const refundDialog = ref(false)
const refunding = ref(false)
const refundRequests = ref([])
const refundForm = { amount: 0, reason: '' }
// F07：确认收货只对 SHIPPED 显示；提交后本地更新状态，无需重新输入凭证。
const confirmingReceipt = ref(false)
const canConfirm = (status) => canConfirmReceipt(status)

const OPEN_COMPLAINT = new Set(['PENDING', 'MERCHANT_REPLIED', 'ARBITRATING'])
const complaints = ref([])
const complaintDialog = ref(false)
const complaintSubmitting = ref(false)
const complaintType = ref('QUALITY')
const complaintContent = ref('')
const complaintEvidence = ref('')
const activeComplaint = computed(() => complaints.value.find(c => OPEN_COMPLAINT.has(c.status)))

const complaintStatusClass = s => ({
  PENDING: 'text-amber-600', MERCHANT_REPLIED: 'text-blue-600', ARBITRATING: 'text-purple-600',
  RESOLVED: 'text-green-600', REJECTED: 'text-slate-400'
}[s] || 'text-slate-500')

const openComplaint = () => {
  complaintType.value = 'QUALITY'
  complaintContent.value = ''
  complaintEvidence.value = ''
  complaintDialog.value = true
}

const submitComplaint = async () => {
  let payload
  try {
    payload = complaintSubmitPayload({
      orderSn: orderResult.value.order.orderSn,
      contact: contact.value,
      queryToken: queryToken.value,
      complaintType: complaintType.value,
      content: complaintContent.value,
      evidenceImages: complaintEvidence.value.split('\n').map(t => t.trim()).filter(Boolean)
    })
  } catch (e) { alert(e.message); return }
  complaintSubmitting.value = true
  try {
    const res = await axios.post('/api/v1/public/orders/complaints', payload)
    if (res.data.code === 200) {
      alert('投诉已提交，商家将尽快答复')
      complaintDialog.value = false
      await loadComplaints()
    } else {
      alert(res.data.message || '提交失败')
    }
  } catch (e) {
    alert(e.response?.data?.message || '提交失败，请稍后重试')
  } finally {
    complaintSubmitting.value = false
  }
}

const escalateComplaint = async (c) => {
  if (!confirm('确认申请平台介入仲裁？平台将根据双方举证作出裁决。')) return
  try {
    const res = await axios.post(`/api/v1/public/orders/complaints/${c.id}/escalate`, {
      orderSn: orderResult.value.order.orderSn,
      contact: contact.value,
      queryToken: queryToken.value,
      note: '商家答复未解决问题，申请平台介入'
    })
    if (res.data.code === 200) {
      alert('已申请平台介入，请等待平台裁决')
      await loadComplaints()
    } else {
      alert(res.data.message || '申请失败')
    }
  } catch (e) {
    alert(e.response?.data?.message || '申请失败，请稍后重试')
  }
}

const loadComplaints = async () => {
  try {
    const res = await axios.get('/api/v1/public/orders/complaints', {
      params: { orderSn: orderResult.value.order.orderSn, contact: contact.value, queryToken: queryToken.value }
    })
    complaints.value = res.data.code === 200 ? (res.data.data || []) : []
  } catch { complaints.value = [] }
}

const REFUNDABLE = new Set(['PAID', 'SHIPPED', 'COMPLETED'])
const canRefund = computed(() => orderResult.value && REFUNDABLE.has(orderResult.value.order.orderStatus))

const refundStatusLabel = s => ({ PENDING: '待商家处理', APPROVED: '已同意退款', REJECTED: '已拒绝', CANCELLED: '已撤回' }[s] || s)
const refundStatusClass = s => ({
  PENDING: 'text-amber-600', APPROVED: 'text-green-600', REJECTED: 'text-red-600', CANCELLED: 'text-slate-400'
}[s] || 'text-slate-500')

const openRefund = () => {
  refundForm.amount = Number(orderResult.value.order.payAmount)
  refundForm.reason = ''
  refundDialog.value = true
}

const submitRefund = async () => {
  const amount = Number(refundForm.amount)
  if (!amount || amount <= 0) { alert('请输入退款金额'); return }
  if (amount > Number(orderResult.value.order.payAmount)) { alert('退款金额不能超过实付金额'); return }
  if (!refundForm.reason.trim()) { alert('请填写退款原因'); return }
  refunding.value = true
  try {
    const res = await axios.post('/api/v1/public/orders/refund-requests', {
      orderSn: orderResult.value.order.orderSn,
      contact: contact.value,
      queryToken: queryToken.value,
      refundAmount: amount,
      reason: refundForm.reason.trim()
    })
    if (res.data.code === 200) {
      alert('退款申请已提交，等待商家处理')
      refundDialog.value = false
      await loadRefundRequests()
    } else {
      alert(res.data.message || '提交失败')
    }
  } catch (e) {
    alert(e.response?.data?.message || '提交失败，请稍后重试')
  } finally {
    refunding.value = false
  }
}

const loadRefundRequests = async () => {
  try {
    const res = await axios.get('/api/v1/public/orders/refund-requests', {
      params: { orderSn: orderResult.value.order.orderSn, contact: contact.value, queryToken: queryToken.value }
    })
    refundRequests.value = res.data.code === 200 ? (res.data.data || []) : []
  } catch { refundRequests.value = [] }
}

const handleConfirmReceipt = async () => {
  if (!orderResult.value) return
  if (!confirm('确认已收到商品？确认后订单将进入售后受理期。')) return
  confirmingReceipt.value = true
  try {
    const res = await axios.post('/api/v1/public/orders/confirm-receipt', {
      orderSn: orderResult.value.order.orderSn,
      contact: contact.value,
      queryToken: queryToken.value
    })
    if (res.data.code === 200) {
      // 只用服务端返回的状态/时间回写当前结果，避免整页重查再次要求敏感凭证。
      orderResult.value.order.orderStatus = res.data.data.orderStatus
      orderResult.value.order.finishTime = res.data.data.finishTime
      orderResult.value.order.afterSaleDeadline = res.data.data.afterSaleDeadline
      alert('确认收货成功，订单已完成')
    } else {
      alert(res.data.message || '确认收货失败')
    }
  } catch (e) {
    alert(e.response?.data?.message || '确认收货失败，请稍后重试')
  } finally {
    confirmingReceipt.value = false
  }
}

const handleSearch = async () => {
  let payload
  try { payload = receiptRequest(orderSn.value, contact.value, queryToken.value) }
  catch (error) { alert(error.message); return }
  loading.value = true
  orderResult.value = null
  try {
    const res = await axios.post('/api/v1/public/orders/quick-search', payload)
    if (res.data.code !== 200) { alert(res.data.message || '查询失败'); return }
    orderResult.value = res.data.data
    await loadRefundRequests()
    await loadComplaints()
  } catch (e) {
    alert(e.response?.data?.message || '未查询到订单信息，请确认订单号与联系方式')
  } finally {
    loading.value = false
  }
}

const copyText = (txt) => {
  navigator.clipboard.writeText(txt)
  alert('已复制到剪贴板！')
}

onMounted(() => {
  const safeQuery = receiptRouteQuery(route.query)
  if (safeQuery.sn) orderSn.value = safeQuery.sn
  // 丢弃旧链接中的敏感参数，避免后续导航继续携带；不自动使用URL中的领取凭证。
  if (Object.keys(route.query).some(key => key !== 'sn') || route.hash) {
    router.replace({ path: route.path, query: safeQuery, hash: '' })
  }
})
</script>

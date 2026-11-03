<!--
文件：QuickQuery.vue
用途：免登录订单查单及卡密、定制服务凭证和物流展示。
维护注意：类型3展示人工交付凭证；仅以文本插值渲染，不能直接v-html注入凭证。领取必须通过POST验证独立queryToken；不得把联系方式/凭证放URL、自动日志或持久化浏览器存储。
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
  </div>
</template>

<script setup>
import { receiptRequest, receiptRouteQuery } from '../utils/buyerReceipt.mjs'
import { serviceVoucherText } from '../utils/goodsType.mjs'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const orderSn = ref('')
const contact = ref('')
const queryToken = ref('')
const orderResult = ref(null)

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

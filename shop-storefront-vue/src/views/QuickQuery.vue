<template>
  <div class="max-w-2xl mx-auto py-4">
    <!-- 标头 -->
    <div class="text-center mb-8">
      <span class="inline-block px-3 py-1 rounded-full bg-brand-100 text-brand-700 text-xs font-bold mb-2">
        ⚡ 16688 招牌特色功能
      </span>
      <h1 class="text-2xl font-black text-slate-900 tracking-tight">免登录极速查单与卡密提取</h1>
      <p class="text-xs text-slate-500 mt-1.5">
        输入下单时生成的订单号及预留手机号/邮箱，即可 3 秒内提取卡密或追踪物流。
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

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-3 rounded-lg bg-brand-500 hover:bg-brand-600 disabled:bg-slate-300 text-white font-bold text-sm transition shadow-sm flex items-center justify-center space-x-2"
        >
          <span v-if="loading">正在防刷验签检索中...</span>
          <span v-else>立即免密检索订单</span>
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
        <span>平台二级分账：<strong class="text-green-600">已直结个人卡</strong></span>
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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const loading = ref(false)
const orderSn = ref('')
const contact = ref('')
const orderResult = ref(null)

const handleSearch = async () => {
  if (!orderSn.value.trim() || !contact.value.trim()) {
    alert('请输入订单号与下单预留手机号/邮箱')
    return
  }
  loading.value = true
  orderResult.value = null
  try {
    const res = await axios.get('/api/v1/public/orders/quick-search', {
      params: {
        orderSn: orderSn.value.trim(),
        contact: contact.value.trim()
      }
    })
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
  if (route.query.sn && route.query.contact) {
    orderSn.value = route.query.sn
    contact.value = route.query.contact
    handleSearch()
  }
})
</script>

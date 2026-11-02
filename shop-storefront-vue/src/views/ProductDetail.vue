<template>
  <div v-if="loading" class="text-center py-16 text-slate-400 text-sm">
    正在加载商品详情与变体数据...
  </div>
  <div v-else-if="product" class="max-w-5xl mx-auto space-y-6">
    <!-- 面包屑导航 -->
    <div class="text-xs text-slate-500 flex items-center space-x-1.5">
      <router-link to="/" class="hover:text-orange-600">全品类大厅</router-link>
      <span>/</span>
      <span class="text-slate-800 font-medium truncate max-w-md">{{ product.title }}</span>
    </div>

    <!-- 16688 经典商品核心详情卡片 -->
    <div class="bg-white rounded-xl border border-slate-200 p-6 md:p-8 shadow-sm grid grid-cols-1 md:grid-cols-12 gap-8">
      <!-- 左侧大图与商家名片 (占 5 列) -->
      <div class="md:col-span-5 flex flex-col justify-between">
        <div>
          <div class="aspect-square bg-slate-100 rounded-lg overflow-hidden border border-slate-200/80">
            <img :src="product.mainImage" :alt="product.title" class="w-full h-full object-cover" />
          </div>
          <!-- 交付类型标签 -->
          <div class="mt-3 flex items-center space-x-2">
            <span
              v-if="product.goodsType === 2"
              class="px-2.5 py-1 rounded bg-amber-500 text-white font-bold text-xs flex items-center space-x-1"
            >
              <span>⚡ 虚拟数字权益 · 付款秒发卡密</span>
            </span>
            <span
              v-else
              class="px-2.5 py-1 rounded bg-blue-600 text-white font-bold text-xs"
            >
              📦 实体物流商品 · 顺丰/中通极速发货
            </span>
          </div>
        </div>

        <!-- 商家资质信用名片 (16688 风格) -->
        <div v-if="store" class="mt-5 p-4 bg-slate-50 rounded-lg border border-slate-200/80">
          <div class="flex items-center justify-between mb-2">
            <div class="flex items-center space-x-2">
              <span class="text-xl">🏪</span>
              <span class="font-bold text-xs text-slate-900">{{ store.storeName }}</span>
            </div>
            <span class="px-2 py-0.5 rounded bg-emerald-100 text-emerald-800 font-semibold text-[10px]">
              官方合规认证
            </span>
          </div>
          <p class="text-[11px] text-slate-500 line-clamp-2">{{ store.notice || '源头直供，品质售后保障。' }}</p>
          <div class="mt-3 pt-2.5 border-t border-slate-200/60 flex items-center justify-between text-xs">
            <span class="text-slate-400">客服联系: {{ store.contactPhone }}</span>
            <router-link :to="`/store/${store.id}`" class="text-orange-600 font-semibold hover:underline">
              进店逛逛 ›
            </router-link>
          </div>
        </div>
      </div>

      <!-- 右侧商品规格、阶梯价格与收银台 (占 7 列) -->
      <div class="md:col-span-7 flex flex-col justify-between">
        <div>
          <h1 class="text-lg md:text-xl font-black text-slate-900 leading-snug">{{ product.title }}</h1>
          <p class="text-xs text-slate-500 mt-1.5">{{ product.subTitle || '源头直发 · 一手批发底价' }}</p>

          <!-- 16688 招牌：阶梯批发价格区间表 (Tier Pricing) -->
          <div class="mt-4 p-4 bg-orange-50/70 rounded-xl border border-orange-200/70">
            <div class="grid grid-cols-3 gap-2 text-center divide-x divide-orange-200">
              <div>
                <div class="text-[11px] text-slate-500 font-medium">1 ~ 9 件 (样品起批)</div>
                <div class="text-xl font-black text-orange-600 mt-1">￥{{ currentPrice(1) }}</div>
              </div>
              <div>
                <div class="text-[11px] text-slate-500 font-medium">10 ~ 49 件 (批量直采)</div>
                <div class="text-xl font-black text-orange-600 mt-1">￥{{ currentPrice(10) }}</div>
              </div>
              <div>
                <div class="text-[11px] text-slate-500 font-medium">≥ 50 件 (大客户底价)</div>
                <div class="text-xl font-black text-orange-600 mt-1">￥{{ currentPrice(50) }}</div>
              </div>
            </div>
            <div class="mt-3 pt-2.5 border-t border-orange-200/60 flex items-center justify-between text-xs text-slate-500">
              <span>当前单件现价: <strong class="text-orange-600 text-sm">￥{{ currentSku?.price || product.minPrice }}</strong></span>
              <span>现货可用总存量: <strong class="text-slate-800">{{ currentSku?.stockQuantity || 0 }}</strong> 件</span>
            </div>
          </div>

          <!-- 变体规格 SKU 选择矩阵 -->
          <div class="mt-5">
            <label class="block text-xs font-bold text-slate-700 uppercase tracking-wider mb-2">选择规格变体 SKU</label>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <button
                v-for="sku in skus"
                :key="sku.id"
                @click="currentSku = sku"
                class="p-3 rounded-lg border text-left text-xs transition relative"
                :class="currentSku?.id === sku.id ? 'border-orange-500 bg-orange-50/30 text-orange-950 font-bold ring-1 ring-orange-500 shadow-sm' : 'border-slate-200 hover:border-slate-300 text-slate-700'"
              >
                <div>{{ formatSpec(sku.specData) }}</div>
                <div class="flex items-center justify-between mt-1 text-[11px]">
                  <span class="text-orange-600 font-extrabold">￥{{ sku.price }}</span>
                  <span class="text-slate-400">库存: {{ sku.stockQuantity }}</span>
                </div>
              </button>
            </div>
          </div>

          <!-- 购买数量与联系方式 -->
          <div class="mt-5 space-y-3.5">
            <div class="flex items-center justify-between">
              <label class="text-xs font-bold text-slate-700">采购数量</label>
              <div class="flex items-center space-x-2">
                <button
                  @click="quantity > 1 ? quantity-- : null"
                  class="w-8 h-8 rounded border border-slate-300 flex items-center justify-center font-bold hover:bg-slate-50 text-slate-700"
                >-</button>
                <input
                  v-model.number="quantity"
                  type="number"
                  min="1"
                  :max="currentSku?.stockQuantity || 1"
                  class="w-16 h-8 text-center text-sm border border-slate-300 rounded font-bold"
                />
                <button
                  @click="quantity < (currentSku?.stockQuantity || 1) ? quantity++ : null"
                  class="w-8 h-8 rounded border border-slate-300 flex items-center justify-center font-bold hover:bg-slate-50 text-slate-700"
                >+</button>
              </div>
            </div>

            <div>
              <label class="block text-xs font-bold text-slate-700 mb-1">
                查单联系方式 <span class="text-red-500">*</span>
                <span class="text-[11px] font-normal text-slate-400">(凭此手机号/邮箱 3 秒免密查单提取卡密或追踪物流)</span>
              </label>
              <input
                v-model="buyerContact"
                type="text"
                placeholder="请输入您的联系手机号或电子邮箱"
                class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
              />
            </div>
          </div>
        </div>

        <!-- 极速结算收银台面板 -->
        <div class="mt-6 pt-4 border-t border-slate-100">
          <div class="flex items-center justify-between text-xs text-slate-500 mb-3">
            <span>结算支付通道 (微信/支付宝官方二级直结)</span>
            <span class="font-bold text-slate-700">持牌专户担保 · 零二清</span>
          </div>

          <div class="flex items-center space-x-3">
            <button
              @click="handleCheckout"
              :disabled="submitting || !currentSku || currentSku.stockQuantity <= 0"
              class="flex-1 py-3.5 rounded-lg bg-orange-600 hover:bg-orange-700 disabled:bg-slate-300 text-white font-black text-sm tracking-wide transition shadow-sm flex items-center justify-center space-x-2"
            >
              <span v-if="submitting">正在调起收银台...</span>
              <span v-else>立即支付 ￥{{ calculateTotal() }}</span>
            </button>

            <!-- ★ 找人代付核心通道 -->
            <button
              @click="openPeerPayModal"
              :disabled="submitting || !currentSku || currentSku.stockQuantity <= 0"
              class="px-5 py-3.5 rounded-lg bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-600 hover:to-rose-700 disabled:bg-slate-300 text-white font-black text-sm tracking-wide transition shadow-sm flex items-center justify-center space-x-1.5"
            >
              <span>🤝 找人代付</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 支付成功与履约交付弹窗 -->
    <div v-if="successResult" class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
      <div class="bg-white rounded-xl max-w-md w-full p-6 shadow-2xl border border-slate-200">
        <div class="text-center">
          <div class="w-12 h-12 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center text-2xl mx-auto mb-3">
            ✓
          </div>
          <h2 class="text-lg font-bold text-slate-900">支付成功！订单已履约</h2>
          <p class="text-xs text-slate-500 mt-1">订单流水号：<code>{{ successResult.orderSn }}</code></p>
        </div>

        <!-- 如果是虚拟卡密商品，立即交付 -->
        <div v-if="successResult.cards && successResult.cards.length > 0" class="mt-5">
          <div class="flex items-center justify-between text-xs font-bold text-slate-700 mb-1.5">
            <span>⚡ 已自动秒发虚拟卡密：</span>
            <button @click="copyAllCards" class="text-orange-600 hover:underline">一键复制全部</button>
          </div>
          <div class="p-3 bg-amber-50 rounded-lg border border-amber-200 font-mono text-xs text-amber-900 space-y-1 select-all">
            <div v-for="(card, i) in successResult.cards" :key="i">
              {{ card }}
            </div>
          </div>
          <p class="text-[11px] text-slate-400 mt-2">
            * 凭下单联系方式 <code class="font-bold text-slate-700">{{ successResult.buyerContact }}</code> 与订单号随时可在【免登录快速查单】中调取。
          </p>
        </div>
        <div v-else class="mt-5 p-3.5 bg-blue-50 rounded-lg border border-blue-100 text-xs text-blue-800 leading-relaxed">
          📦 实物订单已成功推送至商家工作台！卖家安排顺丰/圆通快递打单发货后，您可在查单中心实时追踪运单轨迹。
        </div>

        <div class="mt-6 flex space-x-3">
          <router-link
            :to="`/quick-query?sn=${successResult.orderSn}&contact=${successResult.buyerContact}`"
            class="flex-1 text-center py-2.5 rounded-lg border border-slate-300 text-xs font-bold text-slate-700 hover:bg-slate-50 transition"
          >
            前往免登录查单页
          </router-link>
          <button
            @click="successResult = null"
            class="flex-1 py-2.5 rounded-lg bg-orange-600 text-white text-xs font-bold hover:bg-orange-700 transition"
          >
            完成并继续选购
          </button>
        </div>
      </div>
    </div>

    <!-- ★ 找人代付专属求助弹窗 -->
    <div v-if="peerPayModalOpen" class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
      <div class="bg-white rounded-2xl max-w-lg w-full p-6 shadow-2xl border border-slate-200 relative max-h-[90vh] overflow-y-auto">
        <button 
          @click="closePeerPayModal"
          class="absolute top-4 right-4 w-8 h-8 rounded-full bg-slate-100 hover:bg-slate-200 flex items-center justify-center text-slate-500 font-bold"
        >
          ✕
        </button>

        <!-- 步骤 A: 填写求助信息与留言 -->
        <div v-if="!peerPayResult" class="space-y-4">
          <div class="flex items-center space-x-2">
            <span class="text-2xl">🤝</span>
            <div>
              <h3 class="text-base font-black text-slate-900">找人代付 · 生成微信求助买单卡片</h3>
              <p class="text-xs text-slate-400">生成代付短链与 9:16 微信防封海报，好友直接扫码即可替您付款</p>
            </div>
          </div>

          <!-- 商品简报 -->
          <div class="p-3 bg-slate-50 rounded-xl border border-slate-200/80 flex space-x-3 items-center">
            <img :src="product.mainImage" class="w-14 h-14 rounded-lg object-cover" />
            <div class="flex-1 min-w-0">
              <h4 class="text-xs font-bold text-slate-800 truncate">{{ product.title }}</h4>
              <div class="text-[11px] text-slate-500 mt-0.5">{{ formatSpec(currentSku.specData) }} × {{ quantity }}</div>
              <div class="text-xs font-black text-rose-600 mt-1">代付金额: ￥{{ calculateTotal() }}</div>
            </div>
          </div>

          <!-- 买家联系方式 -->
          <div>
            <label class="block text-xs font-bold text-slate-700 mb-1">
              您的查单手机号 / 邮箱 <span class="text-rose-500">*</span>
            </label>
            <input 
              v-model="buyerContact" 
              type="text" 
              placeholder="请输入您的手机号（好友代付成功后商品归您所有）" 
              class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500"
            />
          </div>

          <!-- 求助代付留言 -->
          <div>
            <label class="block text-xs font-bold text-slate-700 mb-1">
              想对代付好友说的话 (个性求助留言)
            </label>
            <textarea 
              v-model="peerMessage" 
              rows="2" 
              placeholder="好心人帮我买个单呗，谢谢老铁！❤️"
              class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500"
            ></textarea>
            
            <!-- 快捷热词 -->
            <div class="flex flex-wrap gap-1.5 mt-2">
              <span 
                v-for="(msg, i) in quickMessages" 
                :key="i"
                @click="peerMessage = msg"
                class="px-2 py-1 rounded-md bg-rose-50 hover:bg-rose-100 text-rose-700 text-[10px] font-medium cursor-pointer transition border border-rose-200/60"
              >
                {{ msg }}
              </span>
            </div>
          </div>

          <button 
            @click="handleCreatePeerPay"
            :disabled="generatingPeerPay"
            class="w-full py-3.5 rounded-xl bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-600 hover:to-rose-700 text-white text-xs font-black shadow-lg shadow-rose-500/20 transition flex items-center justify-center space-x-1.5 disabled:opacity-50"
          >
            <span v-if="generatingPeerPay">正在生成代付链接与微信海报...</span>
            <span v-else>立即生成微信代付卡片与买单海报 ›</span>
          </button>
        </div>

        <!-- 步骤 B: 生成成功，展示复制链接与 9:16 防封海报 -->
        <div v-else class="space-y-4 text-center">
          <div class="w-12 h-12 bg-rose-100 text-rose-600 rounded-full flex items-center justify-center text-2xl mx-auto shadow-inner">
            🎉
          </div>
          <div>
            <h3 class="text-base font-black text-slate-900">求助代付单已生成！</h3>
            <p class="text-xs text-slate-500 mt-1">您可以直接复制链接发给微信好友，或保存下方海报发朋友圈</p>
          </div>

          <!-- 链接复制框 -->
          <div class="flex items-center space-x-2">
            <input 
              :value="peerPayResult.cashierUrl" 
              readonly 
              class="flex-1 px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg text-xs font-mono text-slate-600 select-all"
            />
            <button 
              @click="copyPeerPayLink"
              class="px-4 py-2 bg-rose-600 text-white text-xs font-bold rounded-lg hover:bg-rose-700 transition"
            >
              复制链接
            </button>
          </div>

          <!-- 9:16 微信海报预览 -->
          <div v-if="peerPayResult.posterBase64" class="p-3 bg-slate-50 rounded-xl border border-slate-200">
            <div class="text-[11px] font-bold text-slate-600 mb-2">微信裂变防封买单海报 (长按可直接保存)：</div>
            <div class="max-h-64 overflow-y-auto rounded-lg border border-slate-200 bg-white">
              <img :src="peerPayResult.posterBase64" class="w-full object-contain" />
            </div>
          </div>

          <!-- 操作按钮行 -->
          <div class="flex space-x-2">
            <a 
              :href="peerPayResult.posterBase64" 
              download="微信好友买单海报.png"
              class="flex-1 py-2.5 rounded-xl border border-rose-300 text-rose-600 font-bold text-xs hover:bg-rose-50 transition block text-center"
            >
              保存海报图片
            </a>
            <router-link 
              :to="`/peer-pay/${peerPayResult.peerPayToken}`"
              class="flex-1 py-2.5 rounded-xl bg-slate-900 text-white font-bold text-xs hover:bg-slate-800 transition block text-center"
            >
              前往代付收银台预览 ›
            </router-link>
          </div>
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
const submitting = ref(false)
const product = ref(null)
const skus = ref([])
const store = ref(null)
const currentSku = ref(null)
const quantity = ref(1)
const buyerContact = ref('13912345678')
const successResult = ref(null)

const currentPrice = (qty) => {
  const base = currentSku.value?.price || product.value?.minPrice || 0
  if (qty >= 50) return (base * 0.85).toFixed(2) // 大客户底价85折
  if (qty >= 10) return (base * 0.92).toFixed(2) // 批量批发92折
  return Number(base).toFixed(2)
}

const calculateTotal = () => {
  const base = currentSku.value?.price || 0
  let unit = base
  if (quantity.value >= 50) unit = base * 0.85
  else if (quantity.value >= 10) unit = base * 0.92
  return (unit * quantity.value).toFixed(2)
}

const formatSpec = (jsonStr) => {
  try {
    const obj = JSON.parse(jsonStr)
    return Object.entries(obj).map(([k, v]) => `${k}: ${v}`).join(' / ')
  } catch (e) {
    return jsonStr
  }
}

const loadProduct = async () => {
  loading.value = true
  try {
    const res = await axios.get(`/api/v1/public/products/${route.params.id}`)
    product.value = res.data.data.product
    skus.value = res.data.data.skus
    store.value = res.data.data.store
    if (skus.value.length > 0) {
      currentSku.value = skus.value[0]
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const handleCheckout = async () => {
  if (!buyerContact.value.trim()) {
    alert('请输入联系手机号或邮箱！')
    return
  }
  submitting.value = true
  try {
    const res = await axios.post('/api/v1/public/orders/checkout', {
      merchantId: product.value.merchantId,
      productId: product.value.id,
      skuId: currentSku.value.id,
      quantity: quantity.value,
      buyerContact: buyerContact.value.trim(),
      paymentChannel: 'WECHAT'
    })
    successResult.value = res.data.data
  } catch (e) {
    alert(e.response?.data?.message || '下单失败')
  } finally {
    submitting.value = false
  }
}

const copyAllCards = () => {
  if (successResult.value?.cards) {
    navigator.clipboard.writeText(successResult.value.cards.join('\n'))
    alert('卡密已成功复制到剪贴板！')
  }
}

// ★ 找人代付核心响应式变量与逻辑
const peerPayModalOpen = ref(false)
const generatingPeerPay = ref(false)
const peerPayResult = ref(null)
const peerMessage = ref('好心人帮我买个单呗，谢谢老铁！❤️')
const quickMessages = [
  '江湖救急，帮我买个单呗~',
  '老铁求代付，回头请你喝奶茶！☕',
  '生日礼物就是它了，球球了~ 🎁',
  '这个太超值了，快帮我拿下！✨',
  '独乐乐不如众乐乐，送你一个么么哒~'
]

const openPeerPayModal = () => {
  peerPayResult.value = null
  peerPayModalOpen.value = true
}

const closePeerPayModal = () => {
  peerPayModalOpen.value = false
  peerPayResult.value = null
}

const handleCreatePeerPay = async () => {
  if (!buyerContact.value.trim()) {
    alert('请输入联系手机号或邮箱，用于接收商品与免密查单！')
    return
  }
  generatingPeerPay.value = true
  try {
    const res = await axios.post('/api/v1/peer-pay/buyer/create', {
      merchantId: product.value.merchantId,
      productId: product.value.id,
      skuId: currentSku.value.id,
      quantity: quantity.value,
      buyerContact: buyerContact.value.trim(),
      peerMessage: peerMessage.value.trim()
    })
    if (res.data.code === 200) {
      peerPayResult.value = res.data.data
    } else {
      alert(res.data.message || '生成代付失败')
    }
  } catch (err) {
    alert(err.response?.data?.message || '生成代付单失败，请检查库存')
  } finally {
    generatingPeerPay.value = false
  }
}

const copyPeerPayLink = () => {
  if (peerPayResult.value?.cashierUrl) {
    navigator.clipboard.writeText(peerPayResult.value.cashierUrl)
    alert('微信代付链接已成功复制到剪贴板！可以直接粘贴发送给好友或群聊。')
  }
}

onMounted(() => {
  loadProduct()
})
</script>

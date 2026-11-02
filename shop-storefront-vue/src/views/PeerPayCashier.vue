<template>
  <div class="min-h-screen bg-slate-100 py-6 px-4 sm:px-6 lg:px-8">
    <div class="max-w-md mx-auto space-y-4">
      
      <!-- 加载中状态 -->
      <div v-if="loading" class="bg-white rounded-2xl p-8 text-center shadow-sm border border-slate-200">
        <div class="animate-spin w-8 h-8 border-4 border-rose-500 border-t-transparent rounded-full mx-auto mb-3"></div>
        <p class="text-sm text-slate-500 font-medium">正在调取安全代付账单...</p>
      </div>

      <!-- 错误异常状态 -->
      <div v-else-if="errorMsg" class="bg-white rounded-2xl p-8 text-center shadow-sm border border-rose-100">
        <div class="w-12 h-12 bg-rose-50 text-rose-500 rounded-full flex items-center justify-center mx-auto mb-3 text-2xl font-bold">!</div>
        <h3 class="text-base font-bold text-slate-800">账单调取失败</h3>
        <p class="text-xs text-slate-500 mt-2">{{ errorMsg }}</p>
        <router-link to="/" class="mt-5 inline-block px-5 py-2 rounded-xl bg-slate-900 text-white text-xs font-semibold hover:bg-slate-800 transition">
          返回商城首页
        </router-link>
      </div>

      <!-- 正常代付收银台内容 -->
      <div v-else-if="detail" class="space-y-4">

        <!-- 顶部店铺与官方背书卡片 -->
        <div class="bg-white rounded-2xl p-4 shadow-sm border border-slate-200/80 flex items-center justify-between">
          <div class="flex items-center space-x-3">
            <img 
              :src="detail.storeLogo || 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=100'" 
              alt="Logo" 
              class="w-10 h-10 rounded-xl object-cover border border-slate-100" 
            />
            <div>
              <div class="flex items-center space-x-1.5">
                <span class="text-sm font-black text-slate-900">{{ detail.storeName }}</span>
                <span class="px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-600 font-bold text-[10px]">
                  ✓ 官方认证
                </span>
              </div>
              <p class="text-[11px] text-slate-400 mt-0.5">
                联系方式: {{ detail.contactPhone || '在线客服保障' }}
              </p>
            </div>
          </div>
          <span class="text-xs font-semibold px-2.5 py-1 rounded-full bg-slate-100 text-slate-600">
            {{ detail.peerPayType === 2 ? '商户推单' : '好友代付' }}
          </span>
        </div>

        <!-- 好友留言求助气泡 (高信任转化) -->
        <div class="bg-gradient-to-br from-rose-500 via-rose-600 to-amber-500 rounded-2xl p-5 text-white shadow-md shadow-rose-500/15 relative overflow-hidden">
          <div class="absolute -right-6 -bottom-6 w-24 h-24 bg-white/10 rounded-full blur-xl"></div>
          <div class="flex items-center justify-between text-xs text-rose-100 font-medium">
            <span>💬 来自好友的买单求助</span>
            <span>买家: {{ detail.maskedBuyerContact }}</span>
          </div>
          <div class="mt-3 bg-white/15 backdrop-blur-sm rounded-xl p-3 text-sm font-medium leading-relaxed border border-white/20">
            “{{ detail.peerMessage || '好心人帮我买个单呗，谢谢老铁！❤️' }}”
          </div>
          <div class="mt-3 flex items-center justify-between text-[11px] text-white/80">
            <span>16688 担保交易 · 极速代付</span>
            <span>付款人免注册免登录</span>
          </div>
        </div>

        <!-- 商品核心详情卡片 -->
        <div class="bg-white rounded-2xl p-5 shadow-sm border border-slate-200/80 space-y-4">
          <div class="flex space-x-4">
            <img 
              :src="detail.productImage" 
              :alt="detail.productTitle" 
              class="w-20 h-20 rounded-xl object-cover border border-slate-100 flex-shrink-0"
            />
            <div class="flex-1 flex flex-col justify-between">
              <div>
                <h3 class="text-sm font-bold text-slate-900 leading-snug line-clamp-2">{{ detail.productTitle }}</h3>
                <div class="mt-1 flex items-center space-x-1.5 flex-wrap">
                  <span class="text-[10px] font-semibold px-2 py-0.5 rounded bg-slate-100 text-slate-600">
                    {{ formatSpec(detail.skuSpec) }}
                  </span>
                  <span class="text-[10px] font-semibold px-2 py-0.5 rounded bg-amber-50 text-amber-700">
                    数量: ×{{ detail.quantity }}
                  </span>
                </div>
              </div>
              <div class="flex items-baseline justify-between mt-2">
                <span class="text-xs text-slate-400">应付代付总额</span>
                <span class="text-lg font-black text-rose-600">￥{{ Number(detail.payAmount).toFixed(2) }}</span>
              </div>
            </div>
          </div>

          <!-- 双轨交付属性与安全仲裁提示 -->
          <div v-if="detail.goodsType === 2" class="p-3 bg-amber-50/80 rounded-xl border border-amber-200/60 text-xs text-amber-900 space-y-1">
            <div class="font-bold flex items-center space-x-1">
              <span>⚡ 线上服务 / 虚拟数字权益</span>
            </div>
            <p class="text-[11px] text-amber-800 leading-relaxed">
              {{ detail.serviceDesc || '本商品为线上交付或自动发卡服务。一经交付凭证，非重大质量问题不可无理由极速退款，退款须经平台超管仲裁。' }}
            </p>
          </div>
          <div v-else class="p-3 bg-blue-50/80 rounded-xl border border-blue-200/60 text-xs text-blue-900 space-y-1">
            <div class="font-bold flex items-center space-x-1">
              <span>📦 实物包裹 · 物流极速发货</span>
            </div>
            <p class="text-[11px] text-blue-800 leading-relaxed">
              代付成功后，商家将通过顺丰/中通直接快递至买家预留地址，买家可在查单中心随时追踪物流动态。
            </p>
          </div>
        </div>

        <!-- 支付操作区域 (已支付 / 锁定中 / 待支付) -->

        <!-- 状态 1: 已完成代付 -->
        <div v-if="detail.peerStatus === 'PAID' || detail.orderStatus === 'COMPLETED' || isPaidSuccess" class="bg-white rounded-2xl p-6 text-center shadow-sm border border-emerald-200 space-y-4">
          <div class="w-14 h-14 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center mx-auto text-3xl font-black shadow-inner">
            ✓
          </div>
          <div>
            <h3 class="text-lg font-black text-slate-900">代付已成功！</h3>
            <p class="text-xs text-slate-500 mt-1">非常感谢您的仗义相助，商品将直接交付给买家好友</p>
          </div>

          <!-- 若有系统已交付的虚拟卡密或凭证展示 -->
          <div v-if="deliveredVoucher || detail.serviceVoucher" class="text-left p-3.5 bg-slate-50 rounded-xl border border-slate-200 text-xs font-mono text-slate-800 space-y-1">
            <div class="text-[11px] text-slate-400 font-sans font-bold">交付凭证信息：</div>
            <div class="text-emerald-700 font-bold select-all break-all">
              {{ deliveredVoucher || detail.serviceVoucher }}
            </div>
          </div>

          <!-- 【我也想开店】逆向流量转化引流卡片 (后台可配) -->
          <div v-if="adBanner" class="mt-5 p-4 rounded-xl bg-gradient-to-r from-orange-500 to-amber-500 text-white text-left shadow-md shadow-orange-500/10">
            <div class="flex items-center justify-between">
              <div>
                <span class="px-2 py-0.5 rounded bg-white/20 text-[10px] font-bold tracking-wide">平台招募</span>
                <h4 class="text-sm font-black mt-1">{{ adBanner.adTitle }}</h4>
                <p class="text-xs text-white/90 mt-0.5 leading-snug">{{ adBanner.adSubTitle }}</p>
              </div>
              <a 
                :href="adBanner.targetUrl || '/merchant/register'" 
                class="ml-3 flex-shrink-0 px-3.5 py-2 rounded-lg bg-white text-orange-600 text-xs font-bold shadow hover:bg-orange-50 transition"
              >
                立即开店 ›
              </a>
            </div>
          </div>
        </div>

        <!-- 状态 2: 两段式已预占锁定中 (RESERVED_LOCK) -->
        <div v-else-if="detail.peerStatus === 'RESERVED_LOCK' || lockCountdown > 0" class="bg-white rounded-2xl p-5 shadow-sm border border-rose-200 space-y-4">
          <div class="flex items-center justify-between pb-3 border-b border-slate-100">
            <div class="flex items-center space-x-2">
              <span class="w-2.5 h-2.5 rounded-full bg-rose-500 animate-pulse"></span>
              <span class="text-xs font-bold text-slate-900">支付锁定中 (独占预占)</span>
            </div>
            <div class="text-xs font-mono font-bold text-rose-600 bg-rose-50 px-2.5 py-1 rounded-md">
              ⏱ 倒计时 {{ formatCountdown(lockCountdown) }}
            </div>
          </div>

          <!-- 支付通道选择 -->
          <div class="space-y-2">
            <div class="text-xs font-bold text-slate-700">选择支付方式：</div>
            
            <!-- 模式 B: 商家独立去中心化直收二维码 (如有配置) -->
            <div v-if="detail.paymentChannelType === 'B' && detail.customPayQrcode" class="p-3 bg-amber-50 rounded-xl border border-amber-200 text-center space-y-2">
              <div class="text-xs font-bold text-amber-900">商户专属微信/支付宝收款码</div>
              <img :src="detail.customPayQrcode" alt="收款码" class="w-48 h-48 mx-auto rounded-lg border border-amber-200 shadow-sm" />
              <p class="text-[11px] text-amber-700">扫码直接转账给商家，完成后点击下方按钮确认</p>
            </div>

            <!-- 通用沙箱 / 微信代付通道 -->
            <div 
              @click="selectedChannel = 'WECHAT'" 
              :class="['p-3 rounded-xl border flex items-center justify-between cursor-pointer transition', selectedChannel === 'WECHAT' ? 'border-emerald-500 bg-emerald-50/50' : 'border-slate-200 hover:border-slate-300']"
            >
              <div class="flex items-center space-x-3">
                <span class="text-2xl">🟢</span>
                <div>
                  <div class="text-xs font-bold text-slate-900">微信极速代付</div>
                  <div class="text-[10px] text-slate-400">支持微信零钱 / 储蓄卡一键买单</div>
                </div>
              </div>
              <span v-if="selectedChannel === 'WECHAT'" class="text-emerald-600 font-bold text-sm">✓</span>
            </div>

            <div 
              @click="selectedChannel = 'ALIPAY'" 
              :class="['p-3 rounded-xl border flex items-center justify-between cursor-pointer transition', selectedChannel === 'ALIPAY' ? 'border-blue-500 bg-blue-50/50' : 'border-slate-200 hover:border-slate-300']"
            >
              <div class="flex items-center space-x-3">
                <span class="text-2xl">🔵</span>
                <div>
                  <div class="text-xs font-bold text-slate-900">支付宝收银台</div>
                  <div class="text-[10px] text-slate-400">支持花呗 / 余额宝付款</div>
                </div>
              </div>
              <span v-if="selectedChannel === 'ALIPAY'" class="text-blue-600 font-bold text-sm">✓</span>
            </div>
          </div>

          <!-- 确认支付按钮 (Mock 测试桩与真实拉起) -->
          <button 
            @click="executePay" 
            :disabled="paying"
            class="w-full py-3.5 rounded-xl bg-gradient-to-r from-rose-600 to-rose-700 text-white text-sm font-black shadow-lg shadow-rose-600/20 hover:from-rose-700 hover:to-rose-800 transition flex items-center justify-center space-x-2 disabled:opacity-50"
          >
            <span v-if="paying">正在结算对账中...</span>
            <span v-else>确认支付 ￥{{ Number(detail.payAmount).toFixed(2) }}</span>
          </button>

          <button 
            @click="cancelLock" 
            class="w-full py-2 text-center text-xs text-slate-400 hover:text-slate-600 font-medium transition"
          >
            放弃支付并释放锁
          </button>
        </div>

        <!-- 状态 3: 初始意向状态 (INIT) -->
        <div v-else class="bg-white rounded-2xl p-5 shadow-sm border border-slate-200/80 space-y-4">
          <div class="flex items-center justify-between text-xs text-slate-500">
            <span>两段式库存保障</span>
            <span class="text-emerald-600 font-semibold">● 现货充足可付</span>
          </div>

          <!-- 调起收银台核心按钮 (原子预占) -->
          <button 
            @click="startPreLock" 
            :disabled="locking"
            class="w-full py-4 rounded-2xl bg-gradient-to-r from-rose-600 via-rose-700 to-amber-600 text-white text-base font-black shadow-xl shadow-rose-600/25 hover:opacity-95 transition flex items-center justify-center space-x-2 disabled:opacity-50"
          >
            <span v-if="locking">正在锁定独占库存...</span>
            <span v-else>立即替TA买单 · ￥{{ Number(detail.payAmount).toFixed(2) }}</span>
          </button>

          <p class="text-[11px] text-slate-400 text-center leading-relaxed">
            * 点击后将为您锁定库存 15 分钟，其他付款人在此期间无法抢付。
          </p>
        </div>

        <!-- 底部微信防封海报预览与下载 -->
        <div v-if="detail.posterUrl" class="bg-white rounded-2xl p-4 shadow-sm border border-slate-200/80 text-center">
          <button 
            @click="showPosterModal = true" 
            class="text-xs font-bold text-rose-600 hover:underline flex items-center justify-center space-x-1 mx-auto"
          >
            <span>🖼️ 查看 9:16 微信裂变买单海报</span>
          </button>
        </div>

      </div>
    </div>

    <!-- 微信海报查看大图弹窗 -->
    <div v-if="showPosterModal" class="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4">
      <div class="bg-white rounded-2xl max-w-sm w-full p-4 relative space-y-3">
        <button 
          @click="showPosterModal = false" 
          class="absolute top-3 right-3 w-7 h-7 bg-slate-100 rounded-full flex items-center justify-center text-slate-500 text-xs font-bold hover:bg-slate-200"
        >
          ✕
        </button>
        <h4 class="text-xs font-bold text-slate-800 text-center">9:16 微信防封分享海报</h4>
        <div class="rounded-xl overflow-hidden border border-slate-200 max-h-[65vh] overflow-y-auto">
          <img :src="detail.posterUrl" alt="买单海报" class="w-full object-contain" />
        </div>
        <div class="flex space-x-2 pt-1">
          <a 
            :href="detail.posterUrl" 
            download="微信买单海报.png" 
            class="flex-1 py-2 text-center rounded-xl bg-rose-600 text-white text-xs font-bold hover:bg-rose-700"
          >
            长按或点击保存海报
          </a>
          <button 
            @click="copyLink" 
            class="px-4 py-2 rounded-xl border border-slate-300 text-xs font-bold text-slate-700 hover:bg-slate-50"
          >
            复制代付链接
          </button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const token = route.params.token

const loading = ref(true)
const locking = ref(false)
const paying = ref(false)
const errorMsg = ref('')
const detail = ref(null)
const selectedChannel = ref('WECHAT')
const showPosterModal = ref(false)
const isPaidSuccess = ref(false)
const deliveredVoucher = ref('')
const adBanner = ref(null)

const lockCountdown = ref(0)
let timer = null

const formatSpec = (specStr) => {
  if (!specStr) return '标准规格'
  try {
    const obj = JSON.parse(specStr)
    return Object.entries(obj).map(([k, v]) => `${v}`).join(' / ')
  } catch (e) {
    return specStr
  }
}

const formatCountdown = (seconds) => {
  if (seconds <= 0) return '00:00'
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`
}

const startCountdownTimer = (seconds) => {
  if (timer) clearInterval(timer)
  lockCountdown.value = seconds
  timer = setInterval(() => {
    if (lockCountdown.value > 0) {
      lockCountdown.value--
    } else {
      clearInterval(timer)
      loadDetail() // 超时刷新恢复 INIT
    }
  }, 1000)
}

const loadDetail = async () => {
  try {
    const res = await axios.get(`/api/v1/peer-pay/detail/${token}`)
    if (res.data.code === 200) {
      detail.value = res.data.data
      if (detail.value.peerStatus === 'RESERVED_LOCK' && detail.value.lockSecondsRemaining > 0) {
        startCountdownTimer(detail.value.lockSecondsRemaining)
      } else if (detail.value.peerStatus === 'PAID') {
        isPaidSuccess.value = true
      }
    } else {
      errorMsg.value = res.data.message || '账单不存在'
    }
  } catch (err) {
    errorMsg.value = err.response?.data?.message || '网络连接异常，无法加载代付账单'
  } finally {
    loading.value = false
  }
}

const loadAdBanner = async () => {
  try {
    const res = await axios.get('/api/v1/peer-pay/success-ad')
    if (res.data.code === 200 && res.data.data) {
      adBanner.value = res.data.data
    }
  } catch (ignored) {}
}

const startPreLock = async () => {
  locking.value = true
  try {
    const res = await axios.post(`/api/v1/peer-pay/pre-lock/${token}`)
    if (res.data.code === 200) {
      detail.value.peerStatus = 'RESERVED_LOCK'
      startCountdownTimer(res.data.data.lockSeconds || 900)
    } else {
      alert(res.data.message)
    }
  } catch (err) {
    alert(err.response?.data?.message || '预占锁定失败，请稍候重试')
    loadDetail()
  } finally {
    locking.value = false
  }
}

const cancelLock = async () => {
  if (!confirm('确定放弃当前支付锁定吗？放弃后其他好友将可抢付。')) return
  try {
    await axios.post(`/api/v1/peer-pay/cancel-lock/${token}`)
    if (timer) clearInterval(timer)
    lockCountdown.value = 0
    detail.value.peerStatus = 'INIT'
  } catch (e) {
    loadDetail()
  }
}

const executePay = async () => {
  paying.value = true
  try {
    const payerId = 'GUEST_WECHAT_' + Math.floor(Math.random() * 899999 + 100000)
    const res = await axios.post(`/api/v1/peer-pay/mock-pay/${token}`, {
      payerId: payerId,
      channel: selectedChannel.value
    })

    if (res.data.code === 200) {
      isPaidSuccess.value = true
      detail.value.peerStatus = 'PAID'
      detail.value.orderStatus = res.data.data.orderStatus
      deliveredVoucher.value = res.data.data.serviceVoucher
      if (timer) clearInterval(timer)
      lockCountdown.value = 0
      loadAdBanner()
    } else {
      alert(res.data.message || '支付失败')
    }
  } catch (err) {
    alert(err.response?.data?.message || '支付异常')
  } finally {
    paying.value = false
  }
}

const copyLink = () => {
  const url = window.location.href
  navigator.clipboard.writeText(url)
  alert('代付链接已复制到剪贴板！')
}

onMounted(() => {
  loadDetail()
  loadAdBanner()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

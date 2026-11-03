<!--
文件：PeerPayCashier.vue
用途：代付账单详情、倒计时、分享海报及结果展示。
维护注意：本页不接收或展示领取凭证、卡密及服务交付内容；真实付款未开放，禁止恢复随机付款人Mock调用。将来取消预占须保存ownerToken并显式授权；本地倒计时不是库存锁或支付状态权威来源。
-->
<template>
  <div class="min-h-screen bg-[#F5F5F7] text-slate-800 antialiased pb-12">
    
    <!-- 顶部极简导航栏 (参考美团原版) -->
    <header class="sticky top-0 z-40 bg-white/95 backdrop-blur-md px-4 py-3 flex items-center justify-between border-b border-slate-100">
      <button 
        @click="handleGoBack" 
        class="w-8 h-8 rounded-full flex items-center justify-center text-slate-700 active:bg-slate-100 transition"
      >
        <svg class="w-5 h-5 text-slate-800" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M15 19l-7-7 7-7" />
        </svg>
      </button>
      <h1 class="text-base font-bold text-slate-900 tracking-tight">代付详情</h1>
      <div class="w-8"></div>
    </header>

    <main class="max-w-md mx-auto px-4 pt-3 space-y-3.5">
      
      <!-- 加载中状态 -->
      <div v-if="loading" class="bg-white rounded-2xl p-8 text-center shadow-sm border border-slate-100 my-4">
        <div class="animate-spin w-8 h-8 border-3 border-[#FFD101] border-t-transparent rounded-full mx-auto mb-3"></div>
        <p class="text-xs text-slate-500 font-medium">正在调取安全代付账单...</p>
      </div>

      <!-- 错误状态 -->
      <div v-else-if="errorMsg" class="bg-white rounded-2xl p-8 text-center shadow-sm border border-rose-100 my-4">
        <div class="w-12 h-12 bg-rose-50 text-rose-500 rounded-full flex items-center justify-center mx-auto mb-3 text-2xl font-bold">!</div>
        <h3 class="text-base font-bold text-slate-900">账单获取失败</h3>
        <p class="text-xs text-slate-500 mt-1.5">{{ errorMsg }}</p>
        <router-link to="/" class="mt-5 inline-block px-5 py-2 rounded-xl bg-slate-900 text-white text-xs font-semibold hover:bg-slate-800 transition">
          返回商城首页
        </router-link>
      </div>

      <!-- 正常展示美团风格代付页面 -->
      <div v-else-if="detail" class="space-y-3.5">

        <!-- 1. 发起人信息栏 (头像 + 昵称/手机 + 好友留言，高保真还原美团) -->
        <div class="flex items-center space-x-3 px-1 py-1">
          <!-- 友善卡通萌宠头像 -->
          <div class="w-12 h-12 rounded-full bg-amber-50 border-2 border-white shadow-sm overflow-hidden flex-shrink-0 flex items-center justify-center">
            <svg class="w-10 h-10 text-slate-700" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="22" fill="#F3F4F6"/>
              <ellipse cx="16" cy="12" rx="4" ry="7" fill="#4B5563"/>
              <ellipse cx="32" cy="12" rx="4" ry="7" fill="#4B5563"/>
              <circle cx="24" cy="25" r="16" fill="#FFFFFF"/>
              <circle cx="18" cy="24" r="2.5" fill="#1F2937"/>
              <circle cx="30" cy="24" r="2.5" fill="#1F2937"/>
              <ellipse cx="15" cy="27" rx="2" ry="1.2" fill="#FCA5A5" opacity="0.7"/>
              <ellipse cx="33" cy="27" rx="2" ry="1.2" fill="#FCA5A5" opacity="0.7"/>
              <path d="M22 28 Q24 31 26 28" stroke="#1F2937" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-sm font-bold text-slate-900 truncate flex items-center space-x-1.5">
              <span>{{ detail.maskedBuyerContact || '好友买家' }}</span>
              <span class="text-[10px] font-semibold px-1.5 py-0.2 rounded-full bg-amber-100 text-amber-800">
                {{ detail.peerPayType === 2 ? '商户推单' : '好友求助' }}
              </span>
            </div>
            <p class="text-xs text-slate-500 truncate mt-0.5 font-normal">
              {{ detail.peerMessage || 'Hi~ 你和我的距离只差一份好物～' }}
            </p>
          </div>
        </div>

        <!-- 2. ★ 核心代付主卡片 (White Card 1 - 需付款、倒计时、须知、黄色主按钮) -->
        <div class="bg-white rounded-2xl p-6 shadow-sm border border-slate-100/80 text-center">
          
          <div class="text-sm font-medium text-slate-500 mb-1">需付款</div>
          
          <!-- 大额数字 -->
          <div class="flex items-baseline justify-center text-slate-900 my-2">
            <span class="text-2xl font-bold mr-0.5">¥</span>
            <span class="text-4xl sm:text-5xl font-black tracking-tight font-sans">
              {{ Number(detail.payAmount).toFixed(2) }}
            </span>
          </div>

          <!-- 倒计时指示器 (黑底圆角数字盒子，参考美团原版 [00] : [14] : [49]) -->
          <div class="flex items-center justify-center space-x-1.5 text-xs text-slate-500 mt-2 mb-4 font-medium">
            <span>支付剩余时间</span>
            <div class="flex items-center space-x-1 font-mono">
              <span class="bg-[#1f2937] text-white px-1.5 py-0.5 rounded text-xs font-bold">{{ countdownHours }}</span>
              <span class="text-slate-800 font-bold">:</span>
              <span class="bg-[#1f2937] text-white px-1.5 py-0.5 rounded text-xs font-bold">{{ countdownMinutes }}</span>
              <span class="text-slate-800 font-bold">:</span>
              <span class="bg-[#1f2937] text-white px-1.5 py-0.5 rounded text-xs font-bold">{{ countdownSeconds }}</span>
            </div>
          </div>

          <!-- 黄色付款须知提示框 (参考美团淡黄色圆角背景) -->
          <div class="bg-[#FEF9E7] border border-[#FDE68A] rounded-xl p-3.5 text-left text-xs text-[#854D0E] space-y-1 my-4">
            <div class="font-bold flex items-center space-x-1 text-xs text-[#92400E]">
              <span>付款须知</span>
            </div>
            <p class="text-[11px] text-[#A16207] leading-relaxed">
              1. 代付订单创建成功后15分钟内未付款，订单会自动取消，你可以重新下单。
            </p>
            <p class="text-[11px] text-[#A16207] leading-relaxed">
              2. 当代付订单退款成功后，实付金额将原路退还代付人。
            </p>
          </div>

          <!-- 状态 A: 已完成代付 -->
          <div v-if="detail.peerStatus === 'PAID' || detail.orderStatus === 'COMPLETED' || isPaidSuccess" class="space-y-3 pt-2">
            <div class="w-12 h-12 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center mx-auto text-2xl font-black shadow-inner">
              ✓
            </div>
            <h3 class="text-base font-black text-slate-900">代付已成功！</h3>
            <p class="text-xs text-slate-500">感谢您的仗义相助，请让买家查询订单交付进度</p>

            <p class="text-xs text-slate-500">商品与服务内容由买家凭独立领取凭证查询，付款分享页不展示交付内容。</p>

            <!-- 【我也想开店】引流位 -->
            <div v-if="adBanner" class="mt-4 p-3.5 rounded-xl bg-gradient-to-r from-amber-500 to-orange-500 text-white text-left shadow-sm">
              <div class="flex items-center justify-between">
                <div>
                  <span class="px-1.5 py-0.5 rounded bg-white/20 text-[10px] font-bold">平台招募</span>
                  <div class="text-xs font-black mt-1">{{ adBanner.adTitle }}</div>
                  <div class="text-[11px] text-white/90 leading-snug">{{ adBanner.adSubTitle }}</div>
                </div>
                <a 
                  :href="adBanner.targetUrl || '/console/'" 
                  class="ml-2 px-3 py-1.5 rounded-lg bg-white text-orange-600 text-xs font-bold hover:bg-orange-50"
                >
                  去开店 ›
                </a>
              </div>
            </div>
          </div>

          <!-- 状态 B: 超时已失效 -->
          <div v-else-if="detail.peerStatus === 'EXPIRED' || (lockCountdown <= 0 && detail.peerStatus !== 'RESERVED_LOCK')" class="space-y-3 pt-2">
            <div class="w-12 h-12 bg-slate-100 text-slate-400 rounded-full flex items-center justify-center mx-auto text-xl font-bold">
              ⏱
            </div>
            <h3 class="text-base font-bold text-slate-800">订单已超时取消</h3>
            <p class="text-xs text-slate-500">该代付单已超过 15 分钟付款时效，请让好友重新发起下单。</p>
            <router-link to="/" class="inline-block mt-2 px-5 py-2.5 rounded-xl bg-slate-900 text-white text-xs font-bold hover:bg-slate-800">
              返回商城逛逛
            </router-link>
          </div>

          <!-- 状态 C: 待付款 / 立即支付操作区 (美团亮黄大圆角按钮) -->
          <div v-else class="space-y-3 pt-1">
            
            <!-- 主按钮：美团经典亮黄 (#FFD101) 立即替TA付款 (全圆角胶囊型) -->
            <button 
              @click="quickPay" 
              :disabled="locking || paying"
              class="w-full py-3.5 rounded-full bg-[#FFD101] hover:bg-[#FFC500] active:scale-[0.99] text-slate-900 font-bold text-base shadow-sm transition flex items-center justify-center space-x-1.5 disabled:opacity-50"
            >
              <span v-if="locking || paying" class="flex items-center space-x-2">
                <div class="w-4 h-4 border-2 border-slate-900 border-t-transparent rounded-full animate-spin"></div>
                <span>{{ locking ? '正在抢占锁定库存...' : '正在拉起微信支付...' }}</span>
              </span>
              <span v-else>
                支付暂未开放（待付 ¥{{ Number(detail.payAmount).toFixed(2) }}）
              </span>
            </button>

            <!-- 次级按钮：发给微信好友 (生成专属口令与分享卡片) -->
            <button 
              @click="handleShareWechat" 
              class="w-full py-2.5 rounded-full bg-slate-50 hover:bg-slate-100 text-slate-700 border border-slate-200 text-xs font-semibold transition flex items-center justify-center space-x-1.5"
            >
              <span>💬 发给微信好友 / 发群代付</span>
            </button>

            <!-- 底部辅助链接 (自己付款 > · 查看买单海报 >) -->
            <div class="pt-1 flex items-center justify-center space-x-4 text-xs text-slate-400">
              <button @click="$router.push(`/product/${detail.productId || 1}`)" class="hover:text-slate-600 transition">
                自己付款 &gt;
              </button>
              <span>·</span>
              <button @click="showPosterModal = true" class="hover:text-slate-600 transition">
                查看买单海报 &gt;
              </button>
            </div>
          </div>

        </div>

        <!-- 3. ★ 商品信息卡片 (White Card 2 - 极简美团风格：店铺名 + 对好友隐藏开关 + 商品图/标题/规格 + 价格) -->
        <div class="bg-white rounded-2xl p-4 shadow-sm border border-slate-100/80 space-y-3">
          
          <!-- 店铺名与隐藏商品开关 (美团原版极简设计) -->
          <div class="flex items-center justify-between pb-3 border-b border-slate-100">
            <span class="text-sm font-bold text-slate-900 tracking-tight">{{ detail.storeName || '货源优选官方旗舰店' }}</span>
            
            <!-- 对好友隐藏商品 iOS 风格 Toggle 开关 -->
            <div class="flex items-center space-x-2 cursor-pointer select-none" @click="hideProductInfo = !hideProductInfo">
              <span class="text-xs text-slate-400">对好友隐藏商品</span>
              <div 
                :class="['w-10 h-5 rounded-full transition-colors relative flex items-center px-0.5', hideProductInfo ? 'bg-amber-400' : 'bg-slate-200']"
              >
                <div 
                  :class="['w-4 h-4 rounded-full bg-white shadow-md transition-transform', hideProductInfo ? 'translate-x-5' : 'translate-x-0']"
                ></div>
              </div>
            </div>
          </div>

          <!-- 商品详情行 (左主图，中间标题与规格，右侧金额与原价划线) -->
          <div class="flex items-start space-x-3 pt-1">
            <!-- 商品主图 -->
            <div class="w-18 h-18 w-[72px] h-[72px] rounded-lg overflow-hidden bg-slate-50 border border-slate-100 flex-shrink-0 relative">
              <img 
                v-if="!hideProductInfo" 
                :src="detail.productImage" 
                :alt="detail.productTitle" 
                class="w-full h-full object-cover"
              />
              <div v-else class="w-full h-full bg-slate-100 flex flex-col items-center justify-center text-slate-400 text-xs">
                <span class="text-2xl">🎁</span>
                <span class="text-[9px] mt-0.5 font-bold text-slate-500">已隐藏</span>
              </div>
            </div>

            <!-- 商品标题、规格、单价 -->
            <div class="flex-1 min-w-0 pr-2">
              <h4 class="text-xs sm:text-sm font-bold text-slate-900 line-clamp-1 leading-snug">
                {{ hideProductInfo ? '好友挑选的精选心愿好物 ✚' : detail.productTitle }}
              </h4>
              <div class="text-[11px] text-slate-400 mt-1 line-clamp-1">
                {{ hideProductInfo ? '规格已隐藏' : formatSpec(detail.skuSpec) }}
              </div>
              <div class="text-[11px] text-slate-400 mt-0.5">
                x{{ detail.quantity || 1 }}
              </div>
            </div>

            <!-- 价格与原价划线 (对齐右上方) -->
            <div class="text-right flex-shrink-0">
              <div class="text-sm font-bold text-slate-900">
                ¥{{ Number(detail.payAmount).toFixed(0) === detail.payAmount ? Number(detail.payAmount).toFixed(0) : Number(detail.payAmount).toFixed(2) }}
              </div>
              <div class="text-[11px] text-slate-400 line-through mt-0.5">
                ¥{{ (Number(detail.payAmount) * 1.38).toFixed(2) }}
              </div>
            </div>
          </div>

          <!-- 双轨履约交付提示 -->
          <div class="pt-2 text-[11px] text-slate-400 border-t border-slate-50 flex items-center justify-between">
            <span v-if="detail.goodsType !== 1" class="text-amber-700 font-medium">
              ⚡ 线上数字服务 / 自动发卡
            </span>
            <span v-else class="text-blue-700 font-medium">
              📦 实物包裹 · 极速快递发货
            </span>
            <span>担保交易保障中</span>
          </div>

        </div>

      </div>

    </main>

    <!-- 微信海报弹窗 -->
    <div v-if="showPosterModal" class="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4">
      <div class="bg-white rounded-2xl max-w-sm w-full p-4 relative space-y-3">
        <button 
          @click="showPosterModal = false" 
          class="absolute top-3 right-3 w-7 h-7 bg-slate-100 rounded-full flex items-center justify-center text-slate-500 text-xs font-bold hover:bg-slate-200"
        >
          ✕
        </button>
        <h4 class="text-xs font-bold text-slate-800 text-center">9:16 微信裂变防封买单海报</h4>
        <div class="rounded-xl overflow-hidden border border-slate-200 max-h-[60vh] overflow-y-auto">
          <img :src="detail?.posterUrl" alt="海报" class="w-full object-contain" />
        </div>
        <div class="flex space-x-2 pt-1">
          <a 
            :href="detail?.posterUrl" 
            download="微信买单海报.png" 
            class="flex-1 py-2 text-center rounded-xl bg-[#FFD101] text-slate-900 text-xs font-bold hover:bg-[#FFC300]"
          >
            保存海报到相册
          </a>
          <button 
            @click="copyCardText" 
            class="px-4 py-2 rounded-xl border border-slate-300 text-xs font-bold text-slate-700 hover:bg-slate-50"
          >
            复制口令
          </button>
        </div>
      </div>
    </div>

    <!-- 微信右上角分享指引蒙层 -->
    <div 
      v-if="showWechatGuide" 
      @click="showWechatGuide = false" 
      class="fixed inset-0 z-50 bg-black/85 flex flex-col items-end p-6 text-white cursor-pointer"
    >
      <div class="flex items-start space-x-2 mr-4 mt-2 animate-bounce">
        <span class="text-4xl">↗️</span>
      </div>
      <div class="bg-white/10 backdrop-blur-md rounded-2xl p-5 mt-3 max-w-xs text-sm leading-relaxed border border-white/20">
        <p class="font-bold text-[#FFD101] text-base mb-1.5">请点击右上角【···】</p>
        <p class="text-xs text-slate-100 leading-normal">
          选择 <span class="text-amber-300 font-bold">“发送给朋友”</span> 或 <span class="text-amber-300 font-bold">“分享到朋友圈”</span>，好友点开直接替您买单！
        </p>
        <div class="mt-4 pt-3 border-t border-white/10 text-[11px] text-slate-400 text-center">点击任意位置关闭</div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'

const route = useRoute()
const router = useRouter()
const token = route.params.token

const handleGoBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/')
  }
}

const isWeChat = ref(/MicroMessenger/i.test(navigator.userAgent))
const showWechatGuide = ref(false)
const hideProductInfo = ref(false)

const loading = ref(true)
const locking = ref(false)
const paying = ref(false)
const errorMsg = ref('')
const detail = ref(null)
const selectedChannel = ref('WECHAT')
const showPosterModal = ref(false)
const isPaidSuccess = ref(false)
const adBanner = ref(null)

const lockCountdown = ref(0)
let timer = null

// 计算黑底倒计时盒子中的 [00] : [14] : [49]
const countdownHours = computed(() => {
  const h = Math.floor(lockCountdown.value / 3600)
  return h < 10 ? `0${h}` : `${h}`
})

const countdownMinutes = computed(() => {
  const m = Math.floor((lockCountdown.value % 3600) / 60)
  return m < 10 ? `0${m}` : `${m}`
})

const countdownSeconds = computed(() => {
  const s = lockCountdown.value % 60
  return s < 10 ? `0${s}` : `${s}`
})

const formatSpec = (specStr) => {
  if (!specStr) return '标准规格'
  try {
    const obj = JSON.parse(specStr)
    return Object.entries(obj).map(([k, v]) => `${v}`).join(' / ')
  } catch (e) {
    return specStr
  }
}

const startCountdownTimer = (seconds) => {
  if (timer) clearInterval(timer)
  lockCountdown.value = Math.max(0, seconds)
  timer = setInterval(() => {
    if (lockCountdown.value > 0) {
      lockCountdown.value--
    } else {
      clearInterval(timer)
      loadDetail() // 超时刷新
    }
  }, 1000)
}

const copyToClipboard = (text, successMsg) => {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => {
      alert(successMsg)
    }).catch(() => {
      fallbackCopy(text, successMsg)
    })
  } else {
    fallbackCopy(text, successMsg)
  }
}

const fallbackCopy = (text, successMsg) => {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.left = '-9999px'
  document.body.appendChild(ta)
  ta.focus()
  ta.select()
  try {
    document.execCommand('copy')
    alert(successMsg)
  } catch (e) {
    alert('复制失败，请手动长按复制：' + text)
  }
  document.body.removeChild(ta)
}

const handleShareWechat = () => {
  if (isWeChat.value) {
    showWechatGuide.value = true
  } else {
    copyCardText()
  }
}

const copyCardText = () => {
  if (!detail.value) return
  const url = window.location.href
  const text = `Hi~你和我的距离只差一份好物～\n来帮我代付吧！\n🎁 商品：${detail.value.productTitle}\n💰 需付款：¥${Number(detail.value.payAmount).toFixed(2)}\n👉 微信直接点开付款：${url}\n（免注册免登录，微信内直接打开即可完成支付）`
  copyToClipboard(text, '✅ 微信专属代付卡片已复制到剪贴板！\n\n您可以直接在微信聊天或群聊中粘贴发送，好友点开直接微信付款。')
}

const loadDetail = async () => {
  try {
    const res = await axios.get(`/api/v1/peer-pay/detail/${token}`)
    if (res.data.code === 200) {
      detail.value = res.data.data
      if (detail.value.productTitle) {
        document.title = `【代付详情】需付款 ¥${Number(detail.value.payAmount).toFixed(2)} - ${detail.value.productTitle}`
      }
      if (detail.value.lockSecondsRemaining > 0 && detail.value.peerStatus !== 'PAID') {
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

// 核心一键支付：自动抢占原子预占并调起支付结算
const quickPay = async () => {
  alert('支付通道暂未开放，当前账单不能付款，尚未扣款或交付商品。')
}

onMounted(() => {
  loadDetail()
  loadAdBanner()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

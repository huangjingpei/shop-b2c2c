<!--
文件：PeerPayCashier.vue
用途：代付账单详情、倒计时、分享海报及结果展示。
维护注意：本页不接收或展示领取凭证、卡密及服务交付内容；真实付款未开放，禁止恢复随机付款人Mock调用。将来取消预占须保存ownerToken并显式授权；本地倒计时不是库存锁或支付状态权威来源。
T28：账单与海报请求使用序号，token 切换时立即清空旧海报并作废旧响应；失败态可重试；复制走 @shared/clipboard.mjs，失败只提示手动复制。
T29：点击代付前调用 pre-lock 预占库存，ownerToken 存 sessionStorage（与 Idempotency-Key 尝试键分离），刷新恢复时回传 X-Reservation-Token；离开/超时/切换 token 调 cancel-lock 释放。上线顺序：后端 T10（8874b6a）必须先于本页发布，禁止前端先发，否则刷新恢复会被误判为“已被其他付款人预占中”。
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
        <button
          type="button"
          @click="loadDetail()"
          class="mt-5 inline-block px-5 py-2 rounded-xl bg-slate-900 text-white text-xs font-semibold hover:bg-slate-800 transition"
        >
          重新加载
        </button>
        <router-link to="/" class="mt-3 block text-xs text-slate-500 hover:text-slate-700">
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

            <!-- T29：预占成功后的库存保留提示（支付通道尚未开放） -->
            <div v-if="reservation" class="rounded-xl bg-emerald-50 border border-emerald-100 px-3 py-2.5 text-left">
              <p class="text-xs font-bold text-emerald-700">库存已为你保留 {{ countdownMinutes }}:{{ countdownSeconds }}</p>
              <p class="text-[11px] text-emerald-600 mt-1 leading-snug">支付通道暂未开放；离开页面或倒计时结束会自动释放库存，不会扣款。</p>
            </div>

            <!-- T29：预占失败明确提示，并提供刷新账单路径 -->
            <div v-if="reserveError" role="alert" class="rounded-xl bg-rose-50 border border-rose-100 px-3 py-2.5 text-left">
              <p class="text-xs font-semibold text-rose-700">{{ reserveError }}</p>
              <button
                type="button"
                @click="refreshBill"
                class="mt-2 px-3 py-1.5 rounded-lg bg-white border border-rose-200 text-rose-700 text-[11px] font-bold hover:bg-rose-100 transition"
              >
                刷新账单后重试
              </button>
            </div>

            <!-- 主按钮：美团经典亮黄 (#FFD101)；已预占时仅提示支付未开放 -->
            <button 
              @click="reservation ? notifyPaymentUnavailable() : startReservation()" 
              :disabled="reserving"
              class="w-full py-3.5 rounded-full bg-[#FFD101] hover:bg-[#FFC500] active:scale-[0.99] text-slate-900 font-bold text-base shadow-sm transition flex items-center justify-center space-x-1.5 disabled:opacity-50"
            >
              <span v-if="reserving" class="flex items-center space-x-2">
                <div class="w-4 h-4 border-2 border-slate-900 border-t-transparent rounded-full animate-spin"></div>
                <span>正在抢占锁定库存...</span>
              </span>
              <span v-else-if="reservation">确认付款（暂未开放）</span>
              <span v-else>
                立即代付（先预占库存，待付 ¥{{ Number(detail.payAmount).toFixed(2) }}）
              </span>
            </button>

            <!-- T29：主动释放预占，避免占用商家库存 -->
            <button
              v-if="reservation"
              type="button"
              @click="cancelReservation"
              class="w-full py-2.5 rounded-full bg-slate-50 hover:bg-slate-100 text-slate-600 border border-slate-200 text-xs font-semibold transition"
            >
              释放库存并退出付款
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
              <button 
                v-if="detail.productId" 
                @click="$router.push(`/product/${detail.productId}`)" 
                class="hover:text-slate-600 transition"
              >
                自己付款 &gt;
              </button>
              <span v-if="detail.productId">·</span>
              <button @click="openPosterModal" class="hover:text-slate-600 transition">
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

            <!-- 价格 (对齐右上方) -->
            <div class="text-right flex-shrink-0">
              <div class="text-sm font-bold text-slate-900">
                ¥{{ Number(detail.payAmount).toFixed(0) === detail.payAmount ? Number(detail.payAmount).toFixed(0) : Number(detail.payAmount).toFixed(2) }}
              </div>
            </div>
          </div>

          <!-- 双轨履约交付提示 -->
          <div class="pt-2 text-[11px] text-slate-400 border-t border-slate-50 flex items-center justify-between">
            <span v-if="detail.goodsType !== 1" class="text-amber-700 font-medium">
              ⚡ 线上数字服务 / 交付以商家说明为准
            </span>
            <span v-else class="text-blue-700 font-medium">
              📦 实物包裹 · 极速快递发货
            </span>
            <span>规范交易审核</span>
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
        <div v-if="posterLoading" class="py-12 text-center text-xs text-slate-500 font-medium">海报加载中，请稍候...</div>
        <div v-else-if="posterBase64 || detail?.posterUrl" class="rounded-xl overflow-hidden border border-slate-200 max-h-[60vh] overflow-y-auto">
          <img :src="posterBase64 || detail?.posterUrl" alt="海报" class="w-full object-contain" />
        </div>
        <div v-else class="py-12 text-center text-xs text-slate-400">未能加载海报图片</div>
        <div class="flex space-x-2 pt-1">
          <a 
            :href="posterBase64 || detail?.posterUrl" 
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
import { ref, computed, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { copyText } from '@shared/clipboard.mjs'
import {
  readOwnerToken,
  saveOwnerToken,
  clearOwnerToken,
  ensureAttemptKey,
  clearAttemptKey,
  buildPreLockHeaders,
  buildCancelHeaders,
  reservationFailureView
} from '../utils/peerPayReservation.mjs'

const route = useRoute()
const router = useRouter()

const handleGoBack = async () => {
  // T29：离开收银台前主动取消预占，避免占用商家库存。
  const token = currentShareToken()
  const ownerToken = reservation.value?.ownerToken || readOwnerToken(token)
  if (token && ownerToken) {
    await releaseReservationFor(token, ownerToken)
    reservation.value = null
  }
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
// T29：预占状态。ownerToken 只保存在 sessionStorage，尝试幂等键与 ownerToken 分离。
const reserving = ref(false)
const reservation = ref(null)
const reserveError = ref('')
const errorMsg = ref('')
const detail = ref(null)
const selectedChannel = ref('WECHAT')
const showPosterModal = ref(false)
const posterLoading = ref(false)
const posterBase64 = ref('')
const isPaidSuccess = ref(false)
const adBanner = ref(null)

const lockCountdown = ref(0)
let timer = null
// T28：账单与海报请求序号；token 切换或重试时递增，旧响应不得写回。
let detailSequence = 0
let posterSequence = 0

const resetPoster = () => {
  posterSequence++
  posterBase64.value = ''
  posterLoading.value = false
  showPosterModal.value = false
}

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

const currentShareToken = () => route.params.token || detail.value?.token

const startCountdownTimer = (seconds) => {
  if (timer) clearInterval(timer)
  lockCountdown.value = Math.max(0, seconds)
  timer = setInterval(() => {
    if (lockCountdown.value > 0) {
      lockCountdown.value--
    } else {
      clearInterval(timer)
      timer = null
      // T29：倒计时结束服务端已过期，本地立即丢弃 ownerToken/尝试键，避免后续误带过期凭据。
      const token = currentShareToken()
      if (token) {
        clearOwnerToken(token)
        clearAttemptKey(token)
      }
      reservation.value = null
      loadDetail()
    }
  }, 1000)
}

const copyToClipboard = async (text, successMsg) => {
  // T28：统一安全上下文判断与降级；失败提示不拼接复制内容。
  const result = await copyText(text)
  alert(result.ok ? successMsg : '复制失败，请手动长按选择后复制')
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

const openPosterModal = async () => {
  showPosterModal.value = true
  if (posterBase64.value) return
  if (detail.value?.posterUrl) {
    posterBase64.value = detail.value.posterUrl
    return
  }
  const token = route.params.token || detail.value?.token
  if (!token) return
  const sequence = ++posterSequence
  posterLoading.value = true
  try {
    const res = await axios.get(`/api/v1/peer-pay/poster/${token}`)
    // T28：token 已切换/关闭弹窗时丢弃迟到的海报。
    if (sequence !== posterSequence) return
    if (res.data?.code === 200 && res.data?.data) {
      posterBase64.value = res.data.data
    }
  } catch (e) {
    console.error('海报加载失败', e)
  } finally {
    if (sequence === posterSequence) posterLoading.value = false
  }
}

const loadDetail = async (currentToken = route.params.token) => {
  if (!currentToken) return
  const sequence = ++detailSequence
  if (timer) clearInterval(timer)
  resetPoster()
  reservation.value = null
  reserveError.value = ''
  loading.value = true
  errorMsg.value = ''
  detail.value = null
  isPaidSuccess.value = false
  try {
    const res = await axios.get(`/api/v1/peer-pay/detail/${currentToken}`)
    // T28：旧账单响应不得覆盖当前 token 的详情。
    if (sequence !== detailSequence) return
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
    if (sequence !== detailSequence) return
    errorMsg.value = err.response?.data?.message || '网络连接异常，无法加载代付账单'
  } finally {
    if (sequence === detailSequence) loading.value = false
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

// T29：取消/释放预占。带 X-Reservation-Token 头，403/404 视为已释放，不阻断本地清理。
const releaseReservationFor = async (token, ownerToken) => {
  if (!token || !ownerToken) return
  try {
    await axios.post(`/api/v1/peer-pay/cancel-lock/${token}`, null, { headers: buildCancelHeaders(ownerToken) })
  } catch (e) {
    // 预占可能已到期或被服务端释放，忽略错误但继续清理本地凭据。
  } finally {
    clearOwnerToken(token)
    clearAttemptKey(token)
  }
}

const notifyPaymentUnavailable = () => {
  alert('支付通道暂未开放，当前账单不能付款，尚未扣款或交付商品。')
}

const refreshBill = () => {
  reserveError.value = ''
  loadDetail()
}

const startReservation = async () => {
  const token = currentShareToken()
  if (!token || reserving.value) return
  reserving.value = true
  reserveError.value = ''
  try {
    const ownerToken = readOwnerToken(token)
    const attemptKey = ensureAttemptKey(token)
    // 契约：尝试幂等键始终发送；仅在刷新恢复时才带 X-Reservation-Token（ownerToken）。
    const res = await axios.post(`/api/v1/peer-pay/pre-lock/${token}`, null, {
      headers: buildPreLockHeaders({ ownerToken, attemptKey })
    })
    if (res.data?.code !== 200 || !res.data?.data) {
      throw { response: { status: res.data?.code, data: res.data } }
    }
    const data = res.data.data
    saveOwnerToken(token, data.ownerToken)
    // 预占成功后本次尝试已完成；下一次付款尝试重新生成键，避免跨尝试复用。
    clearAttemptKey(token)
    reservation.value = { ownerToken: data.ownerToken, lockSeconds: data.lockSeconds ?? 0, expireTime: data.expireTime }
    lockCountdown.value = Math.max(0, Number(data.lockSeconds) || 0)
    if (detail.value) detail.value.peerStatus = data.peerStatus || 'RESERVED_LOCK'
    startCountdownTimer(lockCountdown.value)
  } catch (err) {
    const view = reservationFailureView(err)
    reserveError.value = view.title + '：' + view.detail
  } finally {
    reserving.value = false
  }
}

const cancelReservation = async () => {
  const token = currentShareToken()
  const ownerToken = reservation.value?.ownerToken || readOwnerToken(token)
  await releaseReservationFor(token, ownerToken)
  reservation.value = null
  lockCountdown.value = 0
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (detail.value) detail.value.peerStatus = 'INIT'
  alert('已释放库存预占')
}

watch(() => route.params.token, (newToken, oldToken) => {
  // T29：切换分享 token 前释放旧预占，凭据按 token 隔离。
  if (oldToken && oldToken !== newToken) {
    const ownerToken = readOwnerToken(oldToken)
    if (ownerToken) releaseReservationFor(oldToken, ownerToken)
  }
  if (newToken) {
    loadDetail(newToken)
    loadAdBanner()
  }
}, { immediate: true })

onUnmounted(() => {
  if (timer) clearInterval(timer)
  // T29：页面卸载（含浏览器返回）时释放当前预占。
  const token = currentShareToken()
  const ownerToken = reservation.value?.ownerToken || readOwnerToken(token)
  if (token && ownerToken) releaseReservationFor(token, ownerToken)
  // T28：卸载后丢弃未完成的详情/海报响应
  detailSequence++
  posterSequence++
})
</script>

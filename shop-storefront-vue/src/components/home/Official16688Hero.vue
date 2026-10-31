<template>
  <div class="official-16688-hero-wrapper -mx-4 sm:-mx-6 lg:-mx-8 -mt-6 mb-8">
    <!-- 主 Hero 区 (渐变背景) -->
    <div class="hero-stage-bg">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 pt-10 pb-16">
        <div class="grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
          <!-- 左侧文字与标语 (7 列) -->
          <div class="lg:col-span-7 space-y-4">
            <h1 class="hero-main-title">
              新商入驻 权益加码
            </h1>
            <p class="hero-sub-desc">
              0 元开店、货源扶持，带新商家更快起量、更稳经营。
              <a href="http://localhost:3001" target="_blank" class="text-blue-600 hover:text-blue-700 font-medium ml-1">
                查看开店权益 &gt;
              </a>
            </p>

            <!-- 胶囊 Tab 切换栏 -->
            <div class="hero-pills-bar">
              <button
                v-for="(pill, idx) in pills"
                :key="pill"
                @click="activePill = idx"
                class="hero-pill-btn"
                :class="{ 'active': activePill === idx }"
              >
                {{ pill }}
              </button>
            </div>
          </div>

          <!-- 右侧悬浮动态视觉看板 (5 列) -->
          <div class="lg:col-span-5 relative flex justify-center items-center min-h-[380px]">
            <!-- 光晕与发光圆环 -->
            <div class="hero-glow hero-glow-a"></div>
            <div class="hero-glow hero-glow-b"></div>
            <div class="hero-stage-ring"></div>

            <!-- 主磨砂卡片: 今日经营概览 -->
            <div class="glass-card glass-main">
              <div class="glass-head">
                <span class="glass-badge">经营中枢</span>
                <span class="glass-live">
                  <i class="live-pulse-dot"></i>
                  <span>实时</span>
                </span>
              </div>

              <div class="glass-title">今日经营概览</div>

              <div class="glass-stats">
                <div class="glass-stat">
                  <span>订单</span>
                  <strong>1,286</strong>
                </div>
                <div class="glass-stat">
                  <span>铺货</span>
                  <strong>368</strong>
                </div>
              </div>

              <!-- 5 根动态高度上升柱状图 -->
              <div class="glass-bars">
                <span style="--h: 42%; --d: 0s;"></span>
                <span style="--h: 68%; --d: 0.08s;"></span>
                <span style="--h: 54%; --d: 0.16s;"></span>
                <span style="--h: 86%; --d: 0.24s;"></span>
                <span style="--h: 72%; --d: 0.32s;"></span>
              </div>

              <div class="glass-meta">
                <em>订单增长</em>
                <strong>+28%</strong>
              </div>
            </div>

            <!-- 侧边悬浮浮标: 货源对接 -->
            <div class="glass-card glass-side">
              <div class="glass-icon-box">
                <svg viewBox="0 0 24 24" class="w-5 h-5">
                  <defs>
                    <linearGradient id="supplyInfGrad" x1="0%" y1="50%" x2="100%" y2="50%">
                      <stop offset="0%" stop-color="#9fd4ff" />
                      <stop offset="100%" stop-color="#ffffff" />
                    </linearGradient>
                  </defs>
                  <path
                    class="glass-icon-track"
                    fill="none"
                    stroke="url(#supplyInfGrad)"
                    stroke-width="2.2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4zm0 0c2 2.67 4 4 6 4a4 4 0 0 0 0-8c-2 0-4 1.33-6 4z"
                  />
                  <path
                    class="glass-icon-flow"
                    fill="none"
                    stroke="#ffffff"
                    stroke-width="2.4"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4zm0 0c2 2.67 4 4 6 4a4 4 0 0 0 0-8c-2 0-4 1.33-6 4z"
                  />
                </svg>
              </div>
              <div>
                <div class="text-xs font-bold text-slate-800">货源对接</div>
                <div class="text-[11px] text-slate-400">海量货源一键铺货</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 下半部分: 现在开店 即享6大权益 卡片 -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 -mt-6">
      <div class="bg-white rounded-3xl border border-slate-200/80 p-6 sm:p-10 shadow-lg relative">
        <!-- 标题行 -->
        <div class="text-center mb-8">
          <div class="inline-flex items-center space-x-2">
            <h2 class="text-xl sm:text-2xl font-black text-slate-900">
              现在开店 <span class="text-blue-600">即享6大权益</span>
            </h2>
            <span class="px-1.5 py-0.5 rounded text-[10px] font-bold bg-red-500 text-white shadow-xs">
              限时
            </span>
          </div>
        </div>

        <!-- 6 大权益图标栏 -->
        <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-6 text-center">
          <div v-for="item in benefits" :key="item.title" class="flex flex-col items-center group cursor-pointer">
            <div class="w-14 h-14 rounded-2xl bg-blue-50/80 border border-blue-100/60 flex items-center justify-center text-2xl mb-2.5 shadow-xs group-hover:scale-110 group-hover:shadow-md transition-all duration-200">
              <span class="drop-shadow-xs">{{ item.icon }}</span>
            </div>
            <div class="font-bold text-xs text-slate-800 group-hover:text-blue-600 transition-colors">
              {{ item.title }}
            </div>
          </div>
        </div>

        <!-- 立即开店主按钮 -->
        <div class="mt-8 text-center">
          <a
            href="http://localhost:3001"
            target="_blank"
            class="inline-flex items-center space-x-2 px-10 py-3 rounded-xl bg-blue-600 hover:bg-blue-700 text-white font-bold text-sm shadow-md hover:shadow-lg transition-all duration-200"
          >
            <span>立即0元开店</span>
            <span class="px-1.5 py-0.5 rounded text-[10px] bg-blue-400 text-white font-medium">
              新客立减
            </span>
          </a>
        </div>

        <!-- 底部 2 步流程说明条 -->
        <div class="mt-8 pt-6 border-t border-slate-100 flex flex-col md:flex-row items-center justify-center gap-6 text-xs text-slate-500">
          <div class="font-bold text-slate-800 shrink-0">仅需2步</div>

          <div class="flex items-center space-x-2">
            <span class="w-5 h-5 rounded-full bg-slate-100 text-slate-600 font-bold flex items-center justify-center text-[10px]">
              01
            </span>
            <span class="font-bold text-slate-800">注册商家</span>
            <span>填写邮箱等信息，免费开通商家账号</span>
            <a href="http://localhost:3001" target="_blank" class="text-blue-600 font-bold hover:underline">去注册</a>
            <span class="text-[10px] text-slate-400 bg-slate-100 px-1.5 py-0.2 rounded">约3分钟</span>
          </div>

          <div class="hidden md:block text-slate-300">→</div>

          <div class="flex items-center space-x-2">
            <span class="w-5 h-5 rounded-full bg-slate-100 text-slate-600 font-bold flex items-center justify-center text-[10px]">
              02
            </span>
            <span class="font-bold text-slate-800">开通经营</span>
            <span>开通店铺，自营上架商品或对接供货即可开卖</span>
            <span class="text-[10px] text-emerald-600 bg-emerald-50 px-1.5 py-0.2 rounded font-medium">即刻可用</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const activePill = ref(0)
const pills = [
  '新商利好新政策',
  '货源采购',
  '分销代理',
  '货源广场',
  '开放 API'
]

const benefits = [
  { icon: '💳', title: '0元开店' },
  { icon: '📉', title: '低费率' },
  { icon: '📈', title: '对接货源' },
  { icon: '🛡️', title: '免费直清开户' },
  { icon: '📦', title: '一键商户搬家' },
  { icon: '🌟', title: '新商护航' }
]
</script>

<style scoped>
.hero-stage-bg {
  background: linear-gradient(180deg, #e8f3ff 0%, #f5f9ff 60%, #ffffff 100%);
  overflow: hidden;
}

.hero-main-title {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Noto Sans SC", sans-serif;
  font-size: clamp(34px, 4.5vw, 50px);
  font-weight: 900;
  color: #1d2129;
  line-height: 1.15;
  letter-spacing: -0.02em;
}

.hero-sub-desc {
  font-size: 15px;
  color: #4e5969;
  line-height: 1.7;
}

/* 胶囊栏 */
.hero-pills-bar {
  display: inline-flex;
  align-items: center;
  padding: 4px;
  border-radius: 999px;
  background: rgba(0, 85, 255, 0.05);
  margin-top: 16px;
  max-width: 100%;
  overflow-x: auto;
}

.hero-pill-btn {
  height: 38px;
  padding: 0 18px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #4e5969;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  transition: all 0.2s ease;
  cursor: pointer;
}

.hero-pill-btn:hover {
  color: #1d2129;
}

.hero-pill-btn.active {
  background: #ffffff;
  color: #1d2129;
  font-weight: 700;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

/* 光晕与圆环 */
.hero-glow {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}

.hero-glow-a {
  width: 280px;
  height: 280px;
  right: 20px;
  top: 10px;
  background: radial-gradient(circle, rgba(82, 150, 255, 0.35), rgba(82, 150, 255, 0) 70%);
  filter: blur(8px);
}

.hero-glow-b {
  width: 180px;
  height: 180px;
  left: 20px;
  bottom: 80px;
  background: radial-gradient(circle, rgba(123, 167, 255, 0.3), rgba(123, 167, 255, 0) 72%);
  filter: blur(6px);
}

.hero-stage-ring {
  position: absolute;
  right: 15px;
  top: 15px;
  width: 290px;
  height: 290px;
  border-radius: 50%;
  border: 1px solid rgba(51, 112, 255, 0.12);
  box-shadow: inset 0 0 30px rgba(51, 112, 255, 0.04), 0 0 0 16px rgba(51, 112, 255, 0.03);
  pointer-events: none;
}

/* 磨砂卡片通用 */
.glass-card {
  position: absolute;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.95) 0%, rgba(255, 255, 255, 0.85) 100%);
  border: 1px solid rgba(255, 255, 255, 0.95);
  box-shadow: 0 18px 40px rgba(51, 112, 255, 0.12), inset 0 2px 0 rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(14px);
}

/* 主卡片: 今日经营概览 */
.glass-main {
  right: 20px;
  top: 20px;
  width: 265px;
  padding: 18px;
  z-index: 2;
  animation: floatAnim 6s ease-in-out infinite;
}

.glass-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.glass-badge {
  display: inline-flex;
  padding: 2px 9px;
  border-radius: 999px;
  background: rgba(51, 112, 255, 0.1);
  color: #3370ff;
  font-size: 11px;
  font-weight: 700;
}

.glass-live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: #00b42a;
  font-weight: 700;
}

.live-pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #00b42a;
  box-shadow: 0 0 0 rgba(0, 180, 42, 0.4);
  animation: livePulse 1.8s ease-out infinite;
}

.glass-title {
  margin-top: 10px;
  font-size: 16px;
  font-weight: 800;
  color: #1f2329;
}

.glass-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}

.glass-stat {
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(51, 112, 255, 0.04);
  border: 1px solid rgba(51, 112, 255, 0.06);
}

.glass-stat span {
  display: block;
  font-size: 10px;
  color: #86909c;
}

.glass-stat strong {
  display: block;
  margin-top: 1px;
  font-size: 16px;
  font-weight: 800;
  color: #1f2329;
  letter-spacing: -0.02em;
}

.glass-bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 68px;
  margin-top: 12px;
  padding: 10px 10px 6px;
  border-radius: 12px;
  background: linear-gradient(180deg, #f5f9ff 0%, #eef4ff 100%);
}

.glass-bars span {
  flex: 1;
  height: var(--h);
  border-radius: 5px 5px 2px 2px;
  background: linear-gradient(180deg, #7aa6ff 0%, #3370ff 100%);
  box-shadow: 0 4px 10px rgba(51, 112, 255, 0.2);
  transform-origin: bottom;
  animation: barRise 1.1s ease both;
  animation-delay: var(--d);
}

.glass-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-size: 12px;
}

.glass-meta em {
  font-style: normal;
  color: #86909c;
}

.glass-meta strong {
  color: #00b42a;
  font-weight: 800;
}

/* 侧边浮标: 货源对接 */
.glass-side {
  left: 10px;
  top: 140px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  width: 210px;
  z-index: 3;
  animation: floatAnim 6s ease-in-out infinite 0.8s;
}

.glass-icon-box {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: linear-gradient(155deg, #5b8fff 0%, #2563eb 52%, #1d4ed8 100%);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.25);
}

.glass-icon-track {
  opacity: 0.88;
}

.glass-icon-flow {
  opacity: 0.95;
  stroke-dasharray: 10 70;
  filter: drop-shadow(0 0 2px rgba(255, 255, 255, 0.85));
  animation: iconFlow 2.4s linear infinite;
}

@keyframes floatAnim {
  0%, 100% { transform: translateY(0px); }
  50% { transform: translateY(-8px); }
}

@keyframes barRise {
  from { transform: scaleY(0); }
  to { transform: scaleY(1); }
}

@keyframes livePulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 180, 42, 0.7); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(0, 180, 42, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 180, 42, 0); }
}

@keyframes iconFlow {
  to { stroke-dashoffset: -80; }
}
</style>

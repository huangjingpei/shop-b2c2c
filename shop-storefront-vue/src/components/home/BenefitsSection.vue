<template>
  <section class="bg-gradient-to-br from-slate-900 via-indigo-950 to-slate-900 rounded-2xl p-6 sm:p-8 text-white relative overflow-hidden shadow-md my-4">
    <!-- 装饰性光晕背景 -->
    <div class="absolute -top-24 -right-24 w-96 h-96 bg-orange-500/15 rounded-full blur-3xl pointer-events-none"></div>
    <div class="absolute -bottom-24 -left-24 w-80 h-80 bg-blue-500/15 rounded-full blur-3xl pointer-events-none"></div>

    <div class="relative z-10 max-w-5xl mx-auto">
      <!-- 标题区 -->
      <div class="text-center max-w-2xl mx-auto mb-8">
        <span class="inline-block px-3 py-1 rounded-full bg-orange-500/20 text-orange-400 text-xs font-bold uppercase tracking-wider mb-2 border border-orange-500/30">
          双端赋能 · 互利共赢
        </span>
        <h2 class="text-2xl sm:text-3xl font-black tracking-tight">
          为买家省到极致，为卖家赋能到底
        </h2>
        <p class="text-xs sm:text-sm text-slate-300 mt-2">
          对标 16688 批发流通与现代电商规范，打破传统平台垄断，打造更公平的通用电商网络。
        </p>
      </div>

      <!-- 双栏对比卡片 (采购商权益 VS 供货商家权益) -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <!-- 采购商权益 -->
        <div class="bg-white/5 backdrop-blur-md rounded-xl border border-white/10 p-6 flex flex-col justify-between hover:bg-white/8 transition">
          <div>
            <div class="flex items-center space-x-3 mb-4">
              <div class="w-10 h-10 rounded-lg bg-orange-500/20 text-orange-400 flex items-center justify-center text-xl font-black">
                🛒
              </div>
              <div>
                <h3 class="text-base font-bold text-white">采购商 & 终端买家权益</h3>
                <p class="text-xs text-slate-400">单件起批 · 批发让利 · 极速交付</p>
              </div>
            </div>

            <ul class="space-y-3 text-xs text-slate-200">
              <li class="flex items-start space-x-2">
                <span class="text-orange-400 font-bold">✔</span>
                <span><strong>阶梯批发低价</strong>：1件可试样，10件/50件自动触发大宗阶梯批发让利。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-orange-400 font-bold">✔</span>
                <span><strong>免登录极速查单</strong>：无需繁琐注册，凭下单手机号秒查卡密凭据与物流单号。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-orange-400 font-bold">✔</span>
                <span><strong>微信/支付宝担保</strong>：资金直入央行备付金专户，确认收货后清算，零资金风险。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-orange-400 font-bold">✔</span>
                <span><strong>全品类现货保障</strong>：数码、服装、食品实物顺丰直配，虚拟权益即时秒提。</span>
              </li>
            </ul>
          </div>

          <div class="mt-6 pt-4 border-t border-white/10 flex items-center justify-between">
            <span class="text-xs text-slate-400">体验快捷下单查单</span>
            <router-link to="/quick-query" class="px-4 py-1.5 rounded-lg bg-orange-500 hover:bg-orange-600 text-white font-bold text-xs transition">
              快速查单 ›
            </router-link>
          </div>
        </div>

        <!-- 供货商家权益 -->
        <div class="bg-white/5 backdrop-blur-md rounded-xl border border-white/10 p-6 flex flex-col justify-between hover:bg-white/8 transition">
          <div>
            <div class="flex items-center space-x-3 mb-4">
              <div class="w-10 h-10 rounded-lg bg-blue-500/20 text-blue-400 flex items-center justify-center text-xl font-black">
                🏪
              </div>
              <div>
                <h3 class="text-base font-bold text-white">供货商 & 个人店主权益</h3>
                <p class="text-xs text-slate-400">一张身份证开店 · 货款直入个人银行卡</p>
              </div>
            </div>

            <ul class="space-y-3 text-xs text-slate-200">
              <li class="flex items-start space-x-2">
                <span class="text-blue-400 font-bold">✔</span>
                <span><strong>个人合规免营业执照</strong>：依据《电商法》第十条，小微个人一张身份证即可入驻开店。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-blue-400 font-bold">✔</span>
                <span><strong>货款直入储蓄卡（零二清）</strong>：微信收付通/支付宝直付通直连结算，平台资金不触碰。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-blue-400 font-bold">✔</span>
                <span><strong>双形态履约后台</strong>：支持实物批量打单发货，与虚拟卡密库存自动发货统一调度。</span>
              </li>
              <li class="flex items-start space-x-2">
                <span class="text-blue-400 font-bold">✔</span>
                <span><strong>灵活批发策略</strong>：自由配置单价、阶梯起批量、多规格 SKU 与独立店铺主页。</span>
              </li>
            </ul>
          </div>

          <div class="mt-6 pt-4 border-t border-white/10 flex items-center justify-between">
            <span class="text-xs text-slate-400">立即开设专属店铺</span>
            <a href="http://localhost:3001" target="_blank" class="px-4 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-white font-bold text-xs transition border border-white/20">
              免费入驻开店 ›
            </a>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
</script>

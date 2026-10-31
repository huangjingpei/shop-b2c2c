<template>
  <section class="bg-white rounded-2xl border border-slate-200/90 p-6 sm:p-8 shadow-sm my-6">
    <div class="max-w-5xl mx-auto">
      <!-- 头部介绍 -->
      <div class="text-center max-w-xl mx-auto mb-8">
        <span class="text-xs font-bold text-orange-600 uppercase tracking-wider bg-orange-50 px-2.5 py-1 rounded-full border border-orange-200">
          极速流通 · 交易链路
        </span>
        <h2 class="text-xl sm:text-2xl font-black text-slate-900 mt-2">
          4 步极速交易流，简单透明有保障
        </h2>
        <p class="text-xs text-slate-500 mt-1">
          无论是单件散客购买还是大宗货源批发，均享受全流程标准化交付体系。
        </p>
      </div>

      <!-- 4 步步骤流 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6 relative">
        <div 
          v-for="(step, index) in steps" 
          :key="step.number"
          class="relative flex flex-col items-center text-center group"
        >
          <!-- 步骤数字标签与图标容器 -->
          <div class="relative mb-3">
            <div 
              :class="step.iconBg" 
              class="w-14 h-14 rounded-2xl flex items-center justify-center text-2xl shadow-xs group-hover:scale-110 group-hover:shadow-md transition-all duration-200"
            >
              {{ step.icon }}
            </div>
            <div class="absolute -top-2 -right-2 w-6 h-6 rounded-full bg-slate-900 text-white font-mono font-black text-xs flex items-center justify-center border-2 border-white shadow-xs">
              {{ index + 1 }}
            </div>
          </div>

          <h3 class="text-sm font-bold text-slate-900 group-hover:text-orange-600 transition-colors">
            {{ step.title }}
          </h3>
          <p class="text-xs text-slate-500 mt-1.5 leading-relaxed">
            {{ step.desc }}
          </p>

          <!-- 步骤右箭头 (除了最后一个) -->
          <div 
            v-if="index < steps.length - 1" 
            class="hidden lg:block absolute top-7 -right-3 text-slate-300 font-black text-lg select-none"
          >
            →
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
const steps = [
  {
    number: '01',
    icon: '🔍',
    iconBg: 'bg-orange-50 text-orange-600 border border-orange-100',
    title: '全品类挑选货源',
    desc: '覆盖数码潮品、服装鞋包、日用百货与虚拟卡券，清晰标注实物现货或虚拟秒发。'
  },
  {
    number: '02',
    icon: '📊',
    iconBg: 'bg-blue-50 text-blue-600 border border-blue-100',
    title: '阶梯批发自动算价',
    desc: '单件可试样、10件起批发、量大更优。系统实时根据下单数量自动核算批发让利。'
  },
  {
    number: '03',
    icon: '💳',
    iconBg: 'bg-emerald-50 text-emerald-600 border border-emerald-100',
    title: '官方担保合规支付',
    desc: '直接调用微信支付/支付宝官方接口，资金进入央行备付金专户，平台资金零接触。'
  },
  {
    number: '04',
    icon: '🚀',
    iconBg: 'bg-purple-50 text-purple-600 border border-purple-100',
    title: '极速发货 & 秒提卡密',
    desc: '虚拟商品付款即刻提卡显示；实物商品商家一键打单顺丰直配，凭手机号免登录随时查单。'
  }
]
</script>

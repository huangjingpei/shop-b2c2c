<template>
  <section class="py-2">
    <!-- 16688 核心服务金刚区 (4大金牌优势保障) -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <div 
        v-for="item in advantages" 
        :key="item.title"
        class="bg-white rounded-xl border border-slate-200/90 p-4.5 flex items-start space-x-3.5 shadow-sm hover:shadow-md hover:border-orange-300 transition-all duration-200 group"
      >
        <div 
          :class="item.bgClass" 
          class="w-11 h-11 rounded-xl flex items-center justify-center text-2xl shrink-0 group-hover:scale-110 transition-transform shadow-xs"
        >
          {{ item.icon }}
        </div>
        <div class="flex-1 min-w-0">
          <div class="flex items-center space-x-1.5">
            <h4 class="font-bold text-sm text-slate-900 group-hover:text-orange-600 transition-colors truncate">
              {{ item.title }}
            </h4>
            <span v-if="item.badge" class="px-1.5 py-0.2 rounded text-[10px] font-bold bg-orange-100 text-orange-700">
              {{ item.badge }}
            </span>
          </div>
          <p class="text-xs text-slate-500 mt-1 leading-relaxed line-clamp-2">
            {{ item.desc }}
          </p>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
const advantages = [
  {
    icon: '🏭',
    bgClass: 'bg-blue-50 text-blue-600 border border-blue-100',
    title: '源头厂家一手直采',
    badge: '出厂价',
    desc: '全品类优质货源直供，砍掉层层中间商差价，享受真实大宗出厂底价。'
  },
  {
    icon: '⚡',
    bgClass: 'bg-amber-50 text-amber-600 border border-amber-100',
    title: '虚拟卡密自动秒发',
    badge: '7x24h',
    desc: '全天候自动出密，支付成功即刻提卡交货，支持免登录手机号快速核验提取。'
  },
  {
    icon: '🛡️',
    bgClass: 'bg-emerald-50 text-emerald-600 border border-emerald-100',
    title: '微信直付合规担保',
    badge: '零二清',
    desc: '依据电商法对接官方存管备付金专户二级分账，资金不经平台，交易绝对合规。'
  },
  {
    icon: '📦',
    bgClass: 'bg-purple-50 text-purple-600 border border-purple-100',
    title: '实物现货全国直配',
    badge: '顺丰顺达',
    desc: '全国多仓极速联发，顺丰/圆通快递轨迹全程实时可查，破损售后极速赔付。'
  }
]
</script>

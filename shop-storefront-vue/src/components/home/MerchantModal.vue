<template>
  <div v-if="visible" class="fixed inset-0 z-50 overflow-y-auto flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs">
    <div class="bg-white rounded-2xl max-w-lg w-full p-6 shadow-2xl border border-slate-200 relative animate-in fade-in zoom-in-95 duration-150">
      <!-- 关闭按钮 -->
      <button 
        @click="$emit('close')" 
        class="absolute top-4 right-4 text-slate-400 hover:text-slate-600 w-8 h-8 rounded-full flex items-center justify-center bg-slate-100 hover:bg-slate-200 transition"
      >
        ✕
      </button>

      <!-- 头部 -->
      <div class="flex items-center space-x-3 mb-4">
        <div class="w-12 h-12 rounded-xl bg-orange-100 text-orange-600 flex items-center justify-center text-2xl font-black">
          🏪
        </div>
        <div>
          <h3 class="text-lg font-black text-slate-900">16688 商家入驻 · 个人免照开店</h3>
          <p class="text-xs text-slate-500">依据《电子商务法》第十条，零门槛开启全品类电商</p>
        </div>
      </div>

      <!-- 说明清单 -->
      <div class="space-y-3.5 my-5 text-xs text-slate-600 bg-slate-50 p-4 rounded-xl border border-slate-100">
        <div class="flex items-start space-x-2.5">
          <span class="text-emerald-600 font-bold text-sm shrink-0">✓</span>
          <div>
            <strong class="text-slate-800">零门槛开店，无须工商营业执照</strong>
            <p class="text-slate-500 mt-0.5">小微个人凭一张身份证即可入驻，销售自产农副产品、家庭手工业品或利用自身技能开展无须取得许可的便民劳务活动。</p>
          </div>
        </div>

        <div class="flex items-start space-x-2.5">
          <span class="text-emerald-600 font-bold text-sm shrink-0">✓</span>
          <div>
            <strong class="text-slate-800">官方直付通资金存管（绝对零二清）</strong>
            <p class="text-slate-500 mt-0.5">货款通过微信支付/支付宝官方接口直接结算入您的个人银行卡，平台资金不触碰、不沉淀，合规无死角。</p>
          </div>
        </div>

        <div class="flex items-start space-x-2.5">
          <span class="text-emerald-600 font-bold text-sm shrink-0">✓</span>
          <div>
            <strong class="text-slate-800">实物现货 + 虚拟卡密统一调度</strong>
            <p class="text-slate-500 mt-0.5">同一个商家工作台既可管理实物快递打单，也能批量导入激活码/卡券实现 7x24 小时无人值守自动发货。</p>
          </div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="flex space-x-3">
        <button 
          @click="$emit('close')" 
          class="flex-1 py-2.5 rounded-xl border border-slate-300 text-slate-700 font-bold text-xs hover:bg-slate-50 transition"
        >
          稍后再看
        </button>
        <a 
          href="http://localhost:3001" 
          target="_blank" 
          @click="$emit('close')"
          class="flex-1 py-2.5 rounded-xl bg-orange-600 hover:bg-orange-700 text-white font-bold text-xs text-center transition shadow-sm flex items-center justify-center space-x-1"
        >
          <span>进入商家工作台 ›</span>
        </a>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  visible: {
    type: Boolean,
    default: false
  }
})

defineEmits(['close'])
</script>

<template>
  <section class="py-2">
    <!-- 16688 核心服务金刚区 (使用 16688 官方原生图资) -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <div 
        v-for="item in advantages" 
        :key="item.title"
        class="bg-white rounded-xl border border-slate-200/90 p-4.5 flex items-start space-x-3.5 shadow-sm hover:shadow-md hover:border-orange-400 transition-all duration-200 group"
      >
        <div class="w-12 h-12 rounded-xl flex items-center justify-center p-2 shrink-0 bg-slate-50 border border-slate-100 group-hover:scale-110 transition-transform shadow-xs">
          <img :src="item.img" :alt="item.title" class="w-full h-full object-contain" />
        </div>
        <div class="flex-1 min-w-0">
          <div class="flex items-center space-x-1.5">
            <h4 class="font-bold text-sm text-slate-900 group-hover:text-orange-600 transition-colors truncate">
              {{ item.title }}
            </h4>
            <span v-if="item.badge" class="px-1.5 py-0.2 rounded text-[10px] font-bold bg-orange-100 text-orange-700 shrink-0">
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
import shopImg from '../../assets/16688/shop.png'
import safeImg from '../../assets/16688/safe.png'
import sourceImg from '../../assets/16688/source.png'
import deliverImg from '../../assets/16688/deliver.png'

const advantages = [
  {
    img: sourceImg,
    title: '货源广场 · 源头直供',
    badge: '出厂底价',
    desc: '全品类优质一手货源，实物大宗现货与数字权益，砍掉层层加价。'
  },
  {
    img: safeImg,
    title: '免费直清 · 官方存管',
    badge: '绝对零二清',
    desc: '微信/支付宝直付通官方专户二级直结，货款直达个人银行卡，合规无死角。'
  },
  {
    img: shopImg,
    title: '0元开店 · 免执照',
    badge: '一张身份证',
    desc: '依据《电商法》第十条，小微个人免营业执照合规入驻，开启数字化经营。'
  },
  {
    img: deliverImg,
    title: '履约自动化 · 秒提秒发',
    badge: '7x24h值守',
    desc: '虚拟商品全天候自动出密，实物商品一键打单顺丰直配，凭手机号免登录查单。'
  }
]
</script>

<template>
  <footer class="bg-white border-t border-slate-200 mt-12 text-slate-600">
    <!-- 顶部核心保障横栏 -->
    <div class="border-b border-slate-100 py-8 bg-slate-50/70">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="grid grid-cols-2 md:grid-cols-4 gap-6 text-center">
          <div class="flex flex-col items-center">
            <div class="w-12 h-12 rounded-2xl bg-orange-100 text-orange-600 flex items-center justify-center text-2xl mb-2 font-black">
              🏭
            </div>
            <h4 class="font-bold text-xs text-slate-800">源头直采</h4>
            <p class="text-[11px] text-slate-400 mt-0.5">厂家现货 直抵出厂底价</p>
          </div>

          <div class="flex flex-col items-center">
            <div class="w-12 h-12 rounded-2xl bg-blue-100 text-blue-600 flex items-center justify-center text-2xl mb-2 font-black">
              ⚡
            </div>
            <h4 class="font-bold text-xs text-slate-800">秒提秒发</h4>
            <p class="text-[11px] text-slate-400 mt-0.5">虚拟卡密 7x24h 自动履约</p>
          </div>

          <div class="flex flex-col items-center">
            <div class="w-12 h-12 rounded-2xl bg-emerald-100 text-emerald-600 flex items-center justify-center text-2xl mb-2 font-black">
              🛡️
            </div>
            <h4 class="font-bold text-xs text-slate-800">官方存管</h4>
            <p class="text-[11px] text-slate-400 mt-0.5">微信直付二级分账 零二清</p>
          </div>

          <div class="flex flex-col items-center">
            <div class="w-12 h-12 rounded-2xl bg-purple-100 text-purple-600 flex items-center justify-center text-2xl mb-2 font-black">
              📦
            </div>
            <h4 class="font-bold text-xs text-slate-800">极速配送</h4>
            <p class="text-[11px] text-slate-400 mt-0.5">全国顺丰/圆通联运直达</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 中间多列导航与指引 -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
      <div class="grid grid-cols-2 md:grid-cols-4 gap-8 text-xs">
        <div>
          <h4 class="font-bold text-slate-900 mb-3 text-sm flex items-center space-x-1.5">
            <span>🛒</span>
            <span>采购商指南</span>
          </h4>
          <ul class="space-y-2 text-slate-500">
            <li><router-link to="/quick-query" class="hover:text-orange-600 transition">免登录极速查单</router-link></li>
            <li><router-link to="/quick-query" class="hover:text-orange-600 transition">虚拟卡密提取教程</router-link></li>
            <li><a href="#" class="hover:text-orange-600 transition">阶梯起批与批发折扣</a></li>
            <li><a href="#" class="hover:text-orange-600 transition">实物订单物流轨迹追踪</a></li>
          </ul>
        </div>

        <div>
          <h4 class="font-bold text-slate-900 mb-3 text-sm flex items-center space-x-1.5">
            <span>🏪</span>
            <span>商家入驻与经营</span>
          </h4>
          <ul class="space-y-2 text-slate-500">
            <li><a href="http://localhost:3001" target="_blank" class="hover:text-orange-600 transition">个人免营业执照开店</a></li>
            <li><a href="http://localhost:3001" target="_blank" class="hover:text-orange-600 transition">小微个人进件办理指南</a></li>
            <li><a href="http://localhost:3001" target="_blank" class="hover:text-orange-600 transition">卡密库存自动化导入</a></li>
            <li><a href="http://localhost:3001" target="_blank" class="hover:text-orange-600 transition">商家工作台管理中枢</a></li>
          </ul>
        </div>

        <div>
          <h4 class="font-bold text-slate-900 mb-3 text-sm flex items-center space-x-1.5">
            <span>⚖️</span>
            <span>合规与资金安全</span>
          </h4>
          <ul class="space-y-2 text-slate-500">
            <li><a href="#" class="hover:text-orange-600 transition">《电商法》第十条免照资质</a></li>
            <li><a href="#" class="hover:text-orange-600 transition">微信支付电商收付通专户规范</a></li>
            <li><a href="#" class="hover:text-orange-600 transition">平台资金零接触二清规避公告</a></li>
            <li><a href="#" class="hover:text-orange-600 transition">知识产权与消费者权益保障</a></li>
          </ul>
        </div>

        <div>
          <h4 class="font-bold text-slate-900 mb-3 text-sm flex items-center space-x-1.5">
            <span>📞</span>
            <span>通用电商服务</span>
          </h4>
          <p class="text-slate-500 leading-relaxed mb-3">
            对标 16688 批发与现代零售协同架构，提供全品类实物批发与数字虚拟产品交易中枢。
          </p>
          <div class="inline-block px-3 py-1.5 bg-orange-50 border border-orange-200 rounded-lg text-orange-700 font-bold text-xs">
            服务时间：7 × 24 小时自动值守
          </div>
        </div>
      </div>

      <!-- 底部版权与备案 -->
      <div class="mt-10 pt-6 border-t border-slate-100 text-center text-[11px] text-slate-400 space-y-1.5">
        <p>
          © 2026 16688 货源优选交易平台 · 秉承《中华人民共和国电子商务法》与人民银行合规备付金直结规范
        </p>
        <p class="text-slate-400">
          全品类通用电商平台 (数码/服装/食品/日用实物现货批发 · 虚拟数字卡密极速秒发) · 资金直通银行存管专户 (绝对零二清)
        </p>
      </div>
    </div>
  </footer>
</template>

<script setup>
</script>

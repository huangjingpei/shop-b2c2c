<!--
文件：ProductDetail.vue
用途：商品/SKU选择、待付款建单及买家代付分享。
维护注意：建单重试保留幂等键，业务成功才重置；checkout只建UNPAID单，真实支付未开放；不得根据HTTP成功显示已到账或发卡。类型3为人工服务，参数和金额最终由服务端校验。
T28：详情加载使用请求序号，快速切换商品时旧响应不得覆盖当前商品；失败显示错误态并可重试；不预填手机号；复制走 @shared/clipboard.mjs，失败只提示手动复制，不把卡密原文带入提示。
T30：交付标签与结算说明不得宣传未开通的支付直结、资金存管、秒发或指定快递能力，只说明商品类型与真实状态。
-->
<template>
  <div v-if="loading" class="text-center py-16 text-slate-400 text-sm">
    正在加载商品详情与变体数据...
  </div>
  <div v-else-if="errorMsg" role="alert" class="max-w-md mx-auto text-center py-16 text-slate-500 space-y-3">
    <p class="text-sm font-medium">{{ errorMsg }}</p>
    <button
      type="button"
      @click="retry++"
      class="px-4 py-2 rounded-lg bg-slate-900 text-white text-xs font-bold hover:bg-slate-800 transition"
    >
      重新加载
    </button>
    <router-link to="/" class="block text-xs text-orange-600 hover:underline">返回全品类大厅</router-link>
  </div>
  <div v-else-if="product" class="max-w-5xl mx-auto space-y-6">
    <!-- 面包屑导航 -->
    <div class="text-xs text-slate-500 flex items-center space-x-1.5">
      <router-link to="/" class="hover:text-orange-600">全品类大厅</router-link>
      <span>/</span>
      <span class="text-slate-800 font-medium truncate max-w-md">{{ product.title }}</span>
    </div>

    <!-- 16688 经典商品核心详情卡片 -->
    <div class="bg-white rounded-xl border border-slate-200 p-6 md:p-8 shadow-sm grid grid-cols-1 md:grid-cols-12 gap-8">
      <!-- 左侧大图与商家名片 (占 5 列) -->
      <div class="md:col-span-5 flex flex-col justify-between">
        <div>
          <div class="aspect-square bg-slate-100 rounded-lg overflow-hidden border border-slate-200/80">
            <img :src="product.mainImage" :alt="product.title" class="w-full h-full object-cover" />
          </div>
          <!-- 交付类型标签 -->
          <div class="mt-3 flex items-center space-x-2">
            <span v-if="product.goodsType === 3" class="text-xs font-bold text-blue-700">定制服务 · 人工交付</span>
            <span
              v-if="product.goodsType === 2"
              class="px-2.5 py-1 rounded bg-amber-500 text-white font-bold text-xs flex items-center space-x-1"
            >
              <span>⚡ 虚拟数字权益 · 交付以商家规则为准</span>
            </span>
            <span
              v-else-if="product.goodsType === 1"
              class="px-2.5 py-1 rounded bg-blue-600 text-white font-bold text-xs"
            >
              📦 实体物流商品 · 物流方式以商家发货信息为准
            </span>
          </div>
        </div>

        <!-- 商家资质信用名片 (16688 风格) -->
        <div v-if="store" class="mt-5 p-4 bg-slate-50 rounded-lg border border-slate-200/80">
          <div class="flex items-center justify-between mb-2">
            <div class="flex items-center space-x-2">
              <span class="text-xl">🏪</span>
              <span class="font-bold text-xs text-slate-900">{{ store.storeName }}</span>
            </div>
            <span class="px-2 py-0.5 rounded bg-slate-100 text-slate-700 font-semibold text-[10px]">
              已核验店铺
            </span>
          </div>
          <p class="text-[11px] text-slate-500 line-clamp-2">{{ store.notice || '源头直供，品质售后保障。' }}</p>
          <div class="mt-3 pt-2.5 border-t border-slate-200/60 flex items-center justify-between text-xs">
            <span class="text-slate-400">客服联系: {{ store.contactPhone }}</span>
            <router-link :to="`/store/${store.id}`" class="text-orange-600 font-semibold hover:underline">
              进店逛逛 ›
            </router-link>
          </div>
        </div>
      </div>

      <!-- 右侧商品规格、阶梯价格与收银台 (占 7 列) -->
      <div class="md:col-span-7 flex flex-col justify-between">
        <div>
          <h1 class="text-lg md:text-xl font-black text-slate-900 leading-snug">{{ product.title }}</h1>
          <p class="text-xs text-slate-500 mt-1.5">{{ product.subTitle || '源头直发 · 一手批发底价' }}</p>

          <!-- 整品售罄提示：全部规格无库存时阻断下单并明确告知 -->
          <div v-if="productSoldOut" class="mt-4 p-3.5 rounded-xl bg-slate-100 border border-slate-200 text-xs text-slate-600 flex items-center space-x-2">
            <span class="px-2 py-0.5 rounded bg-slate-700 text-white font-black text-[10px]">已售罄</span>
            <span>本商品全部规格暂时售罄，暂不能下单；可联系店铺咨询补货。</span>
          </div>

          <!-- 规格价格与可用库存展示卡片 -->
          <div class="mt-4 p-4 bg-orange-50/70 rounded-xl border border-orange-200/70">
            <div class="flex items-baseline justify-between">
              <div>
                <span class="text-xs text-orange-600 font-bold">现价 ￥</span>
                <span class="text-2xl font-black text-orange-600">{{ currentPrice() }}</span>
              </div>
              <div class="text-xs text-slate-500">
                现货可用库存: <strong class="text-slate-800 font-bold">{{ currentSku?.stockQuantity || 0 }}</strong> 件
              </div>
            </div>
            <div class="mt-2 pt-2 border-t border-orange-200/60 flex items-center justify-between text-xs text-slate-500">
              <span>当前规格: <strong class="text-slate-700">{{ currentSku ? formatSpec(currentSku.specData) : '请选择规格' }}</strong></span>
              <span>单价透明 · 实价成交</span>
            </div>
          </div>

          <!-- 变体规格 SKU 选择矩阵 -->
          <div class="mt-5">
            <label class="block text-xs font-bold text-slate-700 uppercase tracking-wider mb-2">选择规格变体 SKU</label>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <button
                v-for="sku in skus"
                :key="sku.id"
                @click="currentSku = sku"
                class="p-3 rounded-lg border text-left text-xs transition relative"
                :class="[
                  currentSku?.id === sku.id ? 'border-orange-500 bg-orange-50/30 text-orange-950 font-bold ring-1 ring-orange-500 shadow-sm' : 'border-slate-200 hover:border-slate-300 text-slate-700',
                  isSkuSoldOut(sku) ? 'opacity-60' : ''
                ]"
              >
                <div class="flex items-center justify-between">
                  <span>{{ formatSpec(sku.specData) }}</span>
                  <span v-if="isSkuSoldOut(sku)" class="px-1.5 py-0.5 rounded bg-slate-700 text-white text-[10px] font-black">售罄</span>
                </div>
                <div class="flex items-center justify-between mt-1 text-[11px]">
                  <span class="text-orange-600 font-extrabold">￥{{ sku.price }}</span>
                  <span class="text-slate-400">库存: {{ sku.stockQuantity }}</span>
                </div>
              </button>
            </div>
          </div>

          <!-- 购买数量与联系方式 -->
          <div class="mt-5 space-y-3.5">
            <div class="flex items-center justify-between">
              <label class="text-xs font-bold text-slate-700">采购数量</label>
              <div class="flex items-center space-x-2">
                <button
                  @click="quantity > 1 ? quantity-- : null"
                  class="w-8 h-8 rounded border border-slate-300 flex items-center justify-center font-bold hover:bg-slate-50 text-slate-700"
                >-</button>
                <input
                  v-model.number="quantity"
                  type="number"
                  min="1"
                  :max="currentSku?.stockQuantity || 1"
                  class="w-16 h-8 text-center text-sm border border-slate-300 rounded font-bold"
                />
                <button
                  @click="quantity < (currentSku?.stockQuantity || 1) ? quantity++ : null"
                  class="w-8 h-8 rounded border border-slate-300 flex items-center justify-center font-bold hover:bg-slate-50 text-slate-700"
                >+</button>
              </div>
            </div>

            <div>
              <label class="block text-xs font-bold text-slate-700 mb-1">
                查单联系方式 <span class="text-red-500">*</span>
                <span class="text-[11px] font-normal text-slate-400">(凭此手机号/邮箱 3 秒免密查单提取卡密或追踪物流)</span>
              </label>
              <input
                v-model="buyerContact"
                type="text"
                placeholder="请输入您的联系手机号或电子邮箱"
                class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
              />
            </div>

            <!-- F07 实物收货地址：数字商品/定制服务不展示、不提交 -->
            <div v-if="isPhysical" class="rounded-lg border border-slate-200 bg-slate-50/70 p-3 space-y-2">
              <div class="text-xs font-bold text-slate-700">收货信息 <span class="text-red-500">*</span></div>
              <div class="grid grid-cols-2 gap-2">
                <input
                  v-model="receiverName"
                  type="text"
                  maxlength="32"
                  placeholder="收货人姓名"
                  class="px-3 py-2 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
                />
                <input
                  v-model="receiverPhone"
                  type="tel"
                  maxlength="11"
                  placeholder="收货人手机号"
                  class="px-3 py-2 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
                />
              </div>
              <input
                v-model="receiverRegion"
                type="text"
                maxlength="64"
                placeholder="省 / 市 / 区"
                class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
              />
              <input
                v-model="receiverAddress"
                type="text"
                maxlength="200"
                placeholder="详细地址（街道、门牌号）"
                class="w-full px-3 py-2 rounded-lg border border-slate-300 text-sm focus:outline-none focus:border-orange-500"
              />
            </div>
          </div>
        </div>

        <!-- 极速结算收银台面板 -->
        <div class="mt-6 pt-4 border-t border-slate-100">
          <div class="flex items-center justify-between text-xs text-slate-500 mb-3">
            <span>结算支付通道 (支付能力暂未开放)</span>
            <span class="font-bold text-slate-700">下单仅创建待付款订单</span>
          </div>
          <div v-if="isPhysical" class="flex items-center justify-between text-xs text-slate-500 mb-3">
            <span>商品 ￥{{ calculateTotal() }}</span>
            <span>{{ shippingFee > 0 ? `运费 ￥${shippingFee.toFixed(2)}` : '店铺包邮' }}</span>
          </div>

          <div class="flex items-center space-x-3">
            <button
              @click="handleCheckout"
              :disabled="submitting || !currentSku || currentSku.stockQuantity <= 0"
              class="flex-1 py-3.5 rounded-lg bg-orange-600 hover:bg-orange-700 disabled:bg-slate-300 text-white font-black text-sm tracking-wide transition shadow-sm flex items-center justify-center space-x-2"
            >
              <span v-if="submitting">正在调起收银台...</span>
              <span v-else>创建订单 ￥{{ payableTotal() }}</span>
            </button>

            <!-- ★ 找人代付核心通道 -->
            <button
              @click="openPeerPayModal"
              :disabled="submitting || !currentSku || currentSku.stockQuantity <= 0"
              class="px-5 py-3.5 rounded-lg bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-600 hover:to-rose-700 disabled:bg-slate-300 text-white font-black text-sm tracking-wide transition shadow-sm flex items-center justify-center space-x-1.5"
            >
              <span>🤝 找人代付</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 商家维护的详情图文：图片+纯文本段落按顺序渲染，不使用 v-html 杜绝 XSS -->
    <div v-if="detailItems.length > 0" class="bg-white rounded-2xl border border-slate-100 shadow-sm mt-6 p-6">
      <h2 class="text-base font-black text-slate-900 mb-4">商品详情</h2>
      <div class="space-y-4">
        <template v-for="(item, index) in detailItems" :key="index">
          <img
            v-if="item.type === 'image'"
            :src="item.url"
            :alt="`${product.title} 详情图 ${index + 1}`"
            class="w-full rounded-xl border border-slate-100 object-contain bg-slate-50"
            loading="lazy"
          />
          <p v-else class="text-sm text-slate-700 leading-relaxed whitespace-pre-line">{{ item.content }}</p>
        </template>
      </div>
    </div>

    <!-- 待付款订单信息 -->
    <div v-if="successResult" class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
      <div class="bg-white rounded-xl max-w-md w-full p-6 shadow-2xl border border-slate-200">
        <div class="text-center">
          <div class="w-12 h-12 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center text-2xl mx-auto mb-3">
            ✓
          </div>
          <h2 class="text-lg font-bold text-slate-900">订单已创建，等待付款</h2>
          <p class="text-xs text-amber-700 mt-2">支付通道暂未开放，尚未扣款或交付商品。</p>
          <p class="text-xs text-slate-500 mt-1">订单流水号：<code>{{ successResult.orderSn }}</code></p>
        </div>

        <div class="mt-4 p-3 bg-amber-50 border border-amber-200 rounded text-xs">
          <p class="font-bold">请私密保存订单号和买家领取凭证：</p>
          <code class="block break-all select-all">{{ successResult.queryToken }}</code>
          <p>领取凭证用于查单提货，请勿分享给代付人。关闭窗口前请保存。</p>
        </div>
        <!-- 如果是虚拟卡密商品，立即交付 -->
        <div v-if="successResult.cards && successResult.cards.length > 0" class="mt-5">
          <div class="flex items-center justify-between text-xs font-bold text-slate-700 mb-1.5">
            <span>⚡ 已分配的虚拟卡密：</span>
            <button @click="copyAllCards" class="text-orange-600 hover:underline">一键复制全部</button>
          </div>
          <div class="p-3 bg-amber-50 rounded-lg border border-amber-200 font-mono text-xs text-amber-900 space-y-1 select-all">
            <div v-for="(card, i) in successResult.cards" :key="i">
              {{ card }}
            </div>
          </div>
          <p class="text-[11px] text-slate-400 mt-2">
            * 凭下单联系方式 <code class="font-bold text-slate-700">{{ successResult.buyerContact }}</code> 与订单号随时可在【免登录快速查单】中调取。
          </p>
        </div>
        <div v-else class="mt-5 p-3.5 bg-blue-50 rounded-lg border border-blue-100 text-xs text-blue-800 leading-relaxed">
          请保存订单号。订单当前尚未付款，付款确认前不会分配卡密或安排发货。
        </div>

        <div class="mt-6 flex space-x-3">
          <router-link
            :to="receiptPath(successResult.orderSn)"
            class="flex-1 text-center py-2.5 rounded-lg border border-slate-300 text-xs font-bold text-slate-700 hover:bg-slate-50 transition"
          >
            前往免登录查单页
          </router-link>
          <button
            @click="successResult = null"
            class="flex-1 py-2.5 rounded-lg bg-orange-600 text-white text-xs font-bold hover:bg-orange-700 transition"
          >
            完成并继续选购
          </button>
        </div>
      </div>
    </div>

    <!-- ★ 找人代付专属求助弹窗 -->
    <div v-if="peerPayModalOpen" class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
      <div class="bg-white rounded-2xl max-w-lg w-full p-6 shadow-2xl border border-slate-200 relative max-h-[90vh] overflow-y-auto">
        <button 
          @click="closePeerPayModal"
          class="absolute top-4 right-4 w-8 h-8 rounded-full bg-slate-100 hover:bg-slate-200 flex items-center justify-center text-slate-500 font-bold"
        >
          ✕
        </button>

        <!-- 步骤 A: 填写求助信息与留言 -->
        <div v-if="!peerPayResult" class="space-y-4">
          <div class="flex items-center space-x-2">
            <span class="text-2xl">🤝</span>
            <div>
              <h3 class="text-base font-black text-slate-900">找人代付 · 生成微信求助买单卡片</h3>
              <p class="text-xs text-slate-400">生成代付短链与 9:16 微信防封海报，好友直接扫码即可替您付款</p>
            </div>
          </div>

          <!-- 商品简报 -->
          <div class="p-3 bg-slate-50 rounded-xl border border-slate-200/80 flex space-x-3 items-center">
            <img :src="product.mainImage" class="w-14 h-14 rounded-lg object-cover" />
            <div class="flex-1 min-w-0">
              <h4 class="text-xs font-bold text-slate-800 truncate">{{ product.title }}</h4>
              <div class="text-[11px] text-slate-500 mt-0.5">{{ formatSpec(currentSku.specData) }} × {{ quantity }}</div>
              <div class="text-xs font-black text-rose-600 mt-1">代付金额: ￥{{ payableTotal() }}<span v-if="isPhysical && shippingFee > 0" class="font-normal text-slate-500">（含运费）</span></div>
            </div>
          </div>

          <!-- 买家联系方式 -->
          <div>
            <label class="block text-xs font-bold text-slate-700 mb-1">
              您的查单手机号 / 邮箱 <span class="text-rose-500">*</span>
            </label>
            <input 
              v-model="buyerContact" 
              type="text" 
              placeholder="请输入您的手机号（好友代付成功后商品归您所有）" 
              class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500"
            />
          </div>

          <!-- F07 实物代付单收货信息 -->
          <div v-if="isPhysical" class="rounded-xl border border-rose-200 bg-rose-50/50 p-3 space-y-2">
            <div class="text-xs font-bold text-slate-700">收货信息 <span class="text-rose-500">*</span></div>
            <div class="grid grid-cols-2 gap-2">
              <input v-model="receiverName" type="text" maxlength="32" placeholder="收货人姓名"
                class="px-3 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500" />
              <input v-model="receiverPhone" type="tel" maxlength="11" placeholder="收货人手机号"
                class="px-3 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500" />
            </div>
            <input v-model="receiverRegion" type="text" maxlength="64" placeholder="省 / 市 / 区"
              class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500" />
            <input v-model="receiverAddress" type="text" maxlength="200" placeholder="详细地址（街道、门牌号）"
              class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500" />
          </div>

          <!-- 求助代付留言 -->
          <div>
            <label class="block text-xs font-bold text-slate-700 mb-1">
              想对代付好友说的话 (个性求助留言)
            </label>
            <textarea 
              v-model="peerMessage" 
              rows="2" 
              placeholder="好心人帮我买个单呗，谢谢老铁！❤️"
              class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-xs focus:outline-none focus:border-rose-500"
            ></textarea>
            
            <!-- 快捷热词 -->
            <div class="flex flex-wrap gap-1.5 mt-2">
              <span 
                v-for="(msg, i) in quickMessages" 
                :key="i"
                @click="peerMessage = msg"
                class="px-2 py-1 rounded-md bg-rose-50 hover:bg-rose-100 text-rose-700 text-[10px] font-medium cursor-pointer transition border border-rose-200/60"
              >
                {{ msg }}
              </span>
            </div>
          </div>

          <button 
            @click="handleCreatePeerPay"
            :disabled="generatingPeerPay"
            class="w-full py-3.5 rounded-xl bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-600 hover:to-rose-700 text-white text-xs font-black shadow-lg shadow-rose-500/20 transition flex items-center justify-center space-x-1.5 disabled:opacity-50"
          >
            <span v-if="generatingPeerPay">正在生成代付链接与微信海报...</span>
            <span v-else>立即生成微信代付卡片与买单海报 ›</span>
          </button>
        </div>

        <!-- 步骤 B: 生成成功，展示复制链接与 9:16 防封海报 -->
        <div v-else class="space-y-4 text-center">
          <div class="w-12 h-12 bg-emerald-100 text-emerald-600 rounded-full flex items-center justify-center text-2xl mx-auto shadow-inner">
            🎉
          </div>
          <div>
            <h3 class="text-base font-black text-slate-900">微信代付卡片已生成！</h3>
            <p class="text-xs text-slate-500 mt-1">好友在微信内点击链接即可打开直接买单，免注册免登录</p>
          </div>

          <div class="p-3 bg-amber-50 border border-amber-200 rounded text-xs">
            <p>订单号：<code class="select-all">{{ peerPayResult.orderSn }}</code></p>
            <p>买家领取凭证（请私密保存，不要发给代付人）：</p>
            <code class="block break-all select-all">{{ peerPayResult.queryToken }}</code>
          </div>
          <!-- 核心微信专属复制与分享按钮组 -->
          <div class="space-y-2">
            <!-- 1. 复制微信卡片消息 (最适合直接发微信) -->
            <button 
              @click="copyWechatShareCard"
              class="w-full py-3 px-4 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-black shadow-md shadow-emerald-600/20 transition flex items-center justify-center space-x-2"
            >
              <span>🟢 复制微信代付卡片 (直接粘贴发微信)</span>
            </button>

            <!-- 2. 微信内置浏览器端专属：调起右上角指引 -->
            <button 
              v-if="isWeChat"
              @click="showWechatGuide = true"
              class="w-full py-2.5 px-4 rounded-xl bg-emerald-50 hover:bg-emerald-100 text-emerald-800 border border-emerald-300 text-xs font-bold transition flex items-center justify-center space-x-1.5"
            >
              <span>💬 立即发送给微信好友 / 发群</span>
            </button>

            <!-- 3. 纯链接复制框 -->
            <div class="flex items-center space-x-2 pt-1">
              <input 
                :value="peerPayResult.cashierUrl" 
                readonly 
                class="flex-1 px-3 py-2 bg-slate-50 border border-slate-300 rounded-lg text-xs font-mono text-slate-600 select-all"
              />
              <button 
                @click="copyOnlyLink"
                class="px-3.5 py-2 bg-slate-800 text-white text-xs font-bold rounded-lg hover:bg-slate-900 transition whitespace-nowrap"
              >
                仅复制链接
              </button>
            </div>
          </div>

          <!-- 9:16 微信海报预览 -->
          <div v-if="peerPayResult.posterBase64" class="p-3 bg-slate-50 rounded-xl border border-slate-200">
            <div class="text-[11px] font-bold text-slate-600 mb-2">9:16 微信裂变防封海报 (长按保存发朋友圈)：</div>
            <div class="max-h-56 overflow-y-auto rounded-lg border border-slate-200 bg-white">
              <img :src="peerPayResult.posterBase64" class="w-full object-contain" />
            </div>
          </div>

          <!-- 操作按钮行 -->
          <div class="flex space-x-2">
            <a 
              :href="peerPayResult.posterBase64" 
              download="微信好友买单海报.png"
              class="flex-1 py-2.5 rounded-xl border border-rose-300 text-rose-600 font-bold text-xs hover:bg-rose-50 transition block text-center"
            >
              保存海报图片
            </a>
            <router-link 
              :to="`/peer-pay/${peerPayResult.peerPayToken}`"
              class="flex-1 py-2.5 rounded-xl bg-slate-900 text-white font-bold text-xs hover:bg-slate-800 transition block text-center"
            >
              打开收银台预览 ›
            </router-link>
          </div>
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
        <p class="font-bold text-amber-300 text-base mb-1.5">请点击右上角【···】</p>
        <p class="text-xs text-slate-100 leading-normal">
          选择 <span class="text-emerald-400 font-bold">“发送给朋友”</span> 或 <span class="text-emerald-400 font-bold">“分享到朋友圈”</span>，好友点开直接替您买单！
        </p>
        <div class="mt-4 pt-3 border-t border-white/10 text-[11px] text-slate-400 text-center">点击任意位置关闭</div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { receiptPath } from '../utils/buyerReceipt.mjs'
import { createIdempotentSubmission } from '../utils/idempotency.mjs'
const checkoutSubmission = createIdempotentSubmission()
const peerSubmission = createIdempotentSubmission()
import { ref, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import { loadProductDetail, parseDetailImages, isSkuSoldOut, isProductSoldOut } from '../utils/productDetail.mjs'
import { copyText } from '@shared/clipboard.mjs'
import { isPhysicalGoods, validateReceiver } from '@shared/orderReceipt.mjs'

const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const product = ref(null)
const skus = ref([])
const store = ref(null)
const currentSku = ref(null)
const quantity = ref(1)
const buyerContact = ref('')
// F07：实物订单收货地址；数字商品/定制服务不展示也不提交。
const receiverName = ref('')
const receiverPhone = ref('')
const receiverRegion = ref('')
const receiverAddress = ref('')
const successResult = ref(null)
const errorMsg = ref('')
const retry = ref(0)
let loadSequence = 0

// 详情图文与售罄为纯展示派生：容错解析，不阻断页面加载；下单拦截仍以后端库存校验为准。
const detailItems = computed(() => parseDetailImages(product.value?.detailImages))
const productSoldOut = computed(() => skus.value.length > 0 && isProductSoldOut(skus.value))

const currentPrice = () => {
  const base = currentSku.value?.price || product.value?.minPrice || 0
  return Number(base).toFixed(2)
}

const calculateTotal = () => {
  const base = currentSku.value?.price || 0
  return (base * (quantity.value || 1)).toFixed(2)
}

// F07：实物订单展示店铺固定运费，实付=商品金额+运费；数字商品不显示运费。
const isPhysical = computed(() => isPhysicalGoods(product.value?.goodsType))
const shippingFee = computed(() => {
  if (!isPhysical.value) return 0
  const fee = Number(store.value?.shippingFee ?? 0)
  return Number.isFinite(fee) && fee > 0 ? fee : 0
})
const payableTotal = () => (Number(calculateTotal()) + shippingFee.value).toFixed(2)

const formatSpec = (jsonStr) => {
  try {
    const obj = JSON.parse(jsonStr)
    return Object.entries(obj).map(([k, v]) => `${k}: ${v}`).join(' / ')
  } catch (e) {
    return jsonStr
  }
}

const loadProduct = async (id = route.params.id) => {
  if (!id) return
  const sequence = ++loadSequence
  errorMsg.value = ''
  const result = await loadProductDetail(
    id,
    productId => axios.get(`/api/v1/public/products/${productId}`),
    viewState,
    () => sequence === loadSequence
  )
  // T28：过期请求不写状态、不报错，避免旧商品/旧错误覆盖当前资源。
  if (sequence !== loadSequence) return
  if (!result.ok && !result.stale) errorMsg.value = '商品详情加载失败，请检查网络后重试'
}

// 所有响应式绑定必须先于 watch/immediate 调用声明，避免 setup 阶段同步加载时访问未初始化变量。
const peerPayModalOpen = ref(false)
const generatingPeerPay = ref(false)
const peerPayResult = ref(null)
const peerMessage = ref('好心人帮我买个单呗，谢谢老铁！❤️')
const quickMessages = [
  '江湖救急，帮我买个单呗~',
  '老铁求代付，回头请你喝奶茶！☕',
  '生日礼物就是它了，球球了~ 🎁',
  '这个太超值了，快帮我拿下！✨',
  '独乐乐不如众乐乐，送你一个么么哒~'
]
const isWeChat = ref(/MicroMessenger/i.test(navigator.userAgent))
const showWechatGuide = ref(false)

const viewState = {
  loading,
  product,
  skus,
  store,
  currentSku,
  successResult,
  peerPayResult
}

watch([() => route.params.id, retry], ([newId]) => {
  if (newId) {
    loadProduct(newId)
  }
}, { immediate: true })

// F07：实物订单地址前端先校验，后端仍会二次校验；数字商品返回 null。
const collectReceiver = () => {
  try {
    return validateReceiver(product.value?.goodsType, {
      receiverName: receiverName.value,
      receiverPhone: receiverPhone.value,
      receiverRegion: receiverRegion.value,
      receiverAddress: receiverAddress.value
    })
  } catch (e) {
    alert(e.message)
    return null
  }
}

const handleCheckout = async () => {
  if (!buyerContact.value.trim()) {
    alert('请输入联系手机号或邮箱！')
    return
  }
  const receiver = collectReceiver()
  if (receiver === null) return
  submitting.value = true
  try {
    const payload = {
      merchantId: product.value.merchantId,
      productId: product.value.id,
      skuId: currentSku.value.id,
      quantity: quantity.value,
      buyerContact: buyerContact.value.trim(),
      paymentChannel: 'WECHAT',
      ...(receiver || {})
    }
    const prepared = checkoutSubmission.prepare(payload)
    const res = await axios.post('/api/v1/public/orders/checkout', prepared.body, { headers: prepared.headers })
    if (res.data.code !== 200) {
      alert(res.data.message || '下单失败')
      return
    }
    checkoutSubmission.complete()
    successResult.value = res.data.data
  } catch (e) {
    alert(e.response?.data?.message || '下单失败')
  } finally {
    submitting.value = false
  }
}

const copyAllCards = async () => {
  const cards = successResult.value?.cards
  if (!Array.isArray(cards) || cards.length === 0) return
  // T28：统一安全上下文判断与降级；失败提示不含卡密原文。
  const result = await copyText(cards.join('\n'))
  alert(result.ok ? '卡密已成功复制到剪贴板！' : '复制失败，请手动长按选择后复制')
}

// ★ 找人代付核心逻辑（响应式变量已在加载逻辑之前声明，避免TDZ）
const openPeerPayModal = () => {
  peerPayResult.value = null
  peerPayModalOpen.value = true
}

const closePeerPayModal = () => {
  peerPayModalOpen.value = false
  peerPayResult.value = null
}

const handleCreatePeerPay = async () => {
  if (!buyerContact.value.trim()) {
    alert('请输入联系手机号或邮箱，用于接收商品与免密查单！')
    return
  }
  const receiver = collectReceiver()
  if (receiver === null) return
  generatingPeerPay.value = true
  try {
    const payload = {
      merchantId: product.value.merchantId,
      productId: product.value.id,
      skuId: currentSku.value.id,
      quantity: quantity.value,
      buyerContact: buyerContact.value.trim(),
      peerMessage: peerMessage.value.trim(),
      ...(receiver || {})
    }
    const prepared = peerSubmission.prepare(payload)
    const res = await axios.post('/api/v1/peer-pay/buyer/create', prepared.body, { headers: prepared.headers })
    if (res.data.code === 200) {
      peerSubmission.complete()
      peerPayResult.value = res.data.data
    } else {
      alert(res.data.message || '生成代付失败')
    }
  } catch (err) {
    alert(err.response?.data?.message || '生成代付单失败，请检查库存')
  } finally {
    generatingPeerPay.value = false
  }
}

const copyToClipboard = async (text, successMsg) => {
  const result = await copyText(text)
  alert(result.ok ? successMsg : '复制失败，请手动长按选择后复制')
}

const copyWechatShareCard = () => {
  if (!peerPayResult.value) return
  const text = peerPayResult.value.wechatShareText || 
    `【${peerPayResult.value.peerMessage || '江湖救急，帮我买个单呗~'}】\n🎁 好友求助代付：${product.value?.title || '优质好物'}\n💰 代付金额：￥${peerPayResult.value.payAmount}\n👉 微信点开直接买单：${peerPayResult.value.cashierUrl}\n（微信内打开直接支付，免注册免登录）`
  copyToClipboard(text, '✅ 微信专属代付卡片已复制到剪贴板！\n\n您可以直接在微信聊天或群聊中粘贴发送，好友点开直接微信付款。')
}

const copyOnlyLink = () => {
  if (peerPayResult.value?.cashierUrl) {
    copyToClipboard(peerPayResult.value.cashierUrl, '代付链接已复制到剪贴板！')
  }
}

const copyPeerPayLink = copyOnlyLink
</script>

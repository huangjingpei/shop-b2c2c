<!--
文件：Orders.vue
用途：商家订单查询、推单、实物发货和定制服务履约页面。
维护注意：推单重试须保留幂等键，成功才重置；人工履约仅对类型3且PAID展示，类型2缺卡不能用人工交付替代；按钮可见性不替代服务端租户/状态验证。修改规则同步工具单元测试。
-->
<template>
  <div class="merchant-orders">
    <div class="page-header">
      <div>
        <h2>本店订单履约与代付中心</h2>
        <p>实物物流发货、线上服务录入交付凭证、一键代客推单与三方穿透对账</p>
      </div>
      <div style="display: flex; gap: 10px;">
        <el-button type="primary" plain @click="loadOrders">刷新订单列表</el-button>
        <!-- ★ 代客推单核心按钮 -->
        <el-button type="danger" icon="Share" @click="openPushPayDialog">
          ★ 代客推单 (生成代付账单)
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="orders" stripe style="width: 100%" v-loading="loading">
        <!-- 订单基本信息 -->
        <el-table-column label="订单号 / 下单时间" width="220">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.order.orderSn }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.order.createdAt }}</div>
            <!-- 代付标识徽章 -->
            <div style="margin-top: 4px;">
              <el-tag v-if="row.order.peerPayType === 2" type="danger" size="small" effect="dark">
                ★ 卖家代客推单
              </el-tag>
              <el-tag v-else-if="row.order.peerPayType === 1" type="warning" size="small">
                🤝 买家求助代付
              </el-tag>
              <el-tag v-else type="info" size="small">
                🛒 自行支付
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <!-- 商品与变体明细 -->
        <el-table-column label="购买商品明细" min-width="220">
          <template #default="{ row }">
            <div v-for="item in row.items" :key="item.id">
              <span style="font-weight: 600; color: #0f172a;">{{ item.productTitle }}</span>
              <span style="color: #64748b; font-size: 12px; margin-left: 6px;">{{ item.specInfo }} × {{ item.quantity }}</span>
              
              <!-- 自动发放的卡密 -->
              <div v-if="item.cardDetails" class="card-tag">
                <el-tag type="warning" size="small">已自动发卡</el-tag>
                <span class="card-text">{{ item.cardDetails }}</span>
              </div>
            </div>

            <!-- 线上服务交付凭证展示 -->
            <div v-if="row.order.serviceVoucher" style="margin-top: 6px;">
              <el-tag type="success" size="small">服务交付凭证</el-tag>
              <div class="voucher-box">{{ row.order.serviceVoucher }}</div>
            </div>
          </template>
        </el-table-column>

        <!-- 三方不可篡改对账关系：<买家, 付款人, 留言> -->
        <el-table-column label="三方交易角色对账" width="200">
          <template #default="{ row }">
            <div style="font-size: 12px;">
              <span style="color: #64748b;">提单买家：</span>
              <span style="font-weight: 600;">{{ row.order.buyerContact }}</span>
            </div>
            <div style="font-size: 12px; margin-top: 2px;">
              <span style="color: #64748b;">实际付款人：</span>
              <span v-if="row.order.peerPayerId" style="font-weight: 600; color: #16a34a;">
                {{ row.order.peerPayerId }}
              </span>
              <span v-else style="color: #94a3b8;">待支付</span>
            </div>
            <!-- 代付留言/备注 -->
            <div v-if="row.order.peerMessage" class="peer-msg-box">
              💬 “{{ row.order.peerMessage }}”
            </div>
          </template>
        </el-table-column>

        <!-- 金额与分成 -->
        <el-table-column label="金额与结算" width="150">
          <template #default="{ row }">
            <div>买家实付：<span style="font-weight: 700; color: #e11d48;">￥{{ row.order.payAmount }}</span></div>
            <div style="font-size: 11px; color: #2563eb; margin-top: 2px;">净结应收：￥{{ row.order.merchantSettleAmount }}</div>
            <div style="font-size: 10px; color: #64748b;">(技术抽佣: {{ row.order.commissionRate }}%)</div>
          </template>
        </el-table-column>

        <!-- 订单状态与两段式锁状态 -->
        <el-table-column label="订单 / 代付状态" width="140">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.order.orderStatus)" size="small">
              {{ row.order.orderStatus }}
            </el-tag>
            <div style="margin-top: 4px;">
              <el-tag v-if="row.order.peerStatus === 'RESERVED_LOCK'" type="danger" size="small" effect="plain">
                ⏱ 15分钟已锁库
              </el-tag>
              <el-tag v-else-if="row.order.peerStatus === 'PAID'" type="success" size="small" effect="plain">
                ✓ 代付已清算
              </el-tag>
              <el-tag v-else-if="row.order.peerStatus === 'INIT'" type="info" size="small" effect="plain">
                意向生成未锁库
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <!-- 双轨履约操作列 -->
        <el-table-column label="履约操作" width="160" fixed="right">
          <template #default="{ row }">
            <!-- 实物发货 -->
            <el-button
              v-if="row.order.goodsType === 1 && row.order.orderStatus === 'PAID'"
              type="primary"
              size="small"
              @click="openDeliverDialog(row.order)"
            >
              录入快递发货
            </el-button>

            <!-- 线上定制服务录入交付凭证 -->
            <el-button
              v-else-if="canFulfillService(row.order.goodsType, row.order.orderStatus)"
              type="success"
              size="small"
              @click="openVirtualFulfillDialog(row.order)"
            >
              录入交付凭证
            </el-button>

            <!-- 已完结状态 -->
            <span v-else-if="row.order.orderStatus === 'COMPLETED'" style="font-size: 12px; color: #16a34a; font-weight: 600;">
              ✓ 履约已完结
            </span>
            <span v-else-if="row.order.orderStatus === 'SHIPPED'" style="font-size: 11px; color: #64748b;">
              {{ row.order.logisticsCompany }}<br>{{ row.order.trackingNumber }}
            </span>
            <span v-else style="font-size: 12px; color: #94a3b8;">待付款</span>

            <!-- 查看代付海报按钮 -->
            <div v-if="row.order.posterUrl" style="margin-top: 4px;">
              <el-button type="info" link size="small" @click="viewPoster(row.order)">
                🖼️ 查看海报
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 1. 实物订单快递发货弹窗 -->
    <el-dialog v-model="deliverVisible" title="实物订单录入运单发货" width="440px">
      <el-form label-width="90px">
        <el-form-item label="快递公司" required>
          <el-select v-model="deliverForm.logisticsCompany" style="width: 100%;">
            <el-option label="顺丰速运" value="顺丰速运" />
            <el-option label="圆通速递" value="圆通速递" />
            <el-option label="中通快递" value="中通快递" />
            <el-option label="韵达速递" value="韵达速递" />
          </el-select>
        </el-form-item>
        <el-form-item label="快递单号" required>
          <el-input v-model="deliverForm.trackingNumber" placeholder="请输入运单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deliverVisible = false">取消</el-button>
        <el-button type="primary" @click="handleDeliver">确认发货</el-button>
      </template>
    </el-dialog>

    <!-- 2. 线上服务录入交付凭证弹窗 (锁定不可退款) -->
    <el-dialog v-model="virtualFulfillVisible" title="线上服务录入交付凭证 (履约完成)" width="500px">
      <el-alert
        title="合规提示：线上服务一经录入交付凭证并完成履约，系统将锁定极速无理由退款，退款须经平台超管仲裁。"
        type="warning"
        :closable="false"
        style="margin-bottom: 16px;"
      />
      <el-form label-width="100px">
        <el-form-item label="交付凭证内容" required>
          <el-input
            v-model="virtualVoucherText"
            type="textarea"
            rows="4"
            placeholder="请输入交付凭据（例如：百度网盘链接与提取码、企业部署验收报告、授权激活信息等）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="virtualFulfillVisible = false">取消</el-button>
        <el-button type="success" :loading="fulfilling" @click="handleVirtualFulfill">确认交付履约</el-button>
      </template>
    </el-dialog>

    <!-- 3. ★ 代客推单弹窗 (商户自主议价、生成专属短链与海报) -->
    <el-dialog v-model="pushPayVisible" title="★ 代客推单：创建定制代付账单" width="560px">
      <!-- 步骤 1: 配置推单参数 -->
      <div v-if="!pushPayResult">
        <el-form label-width="110px">
          <el-form-item label="选择推单商品" required>
            <el-select 
              v-model="pushForm.productId" 
              placeholder="请选择商品" 
              style="width: 100%;" 
              @change="handleProductChange"
            >
              <el-option 
                v-for="p in myProducts" 
                :key="p.product.id" 
                :label="p.product.title" 
                :value="p.product.id" 
              />
            </el-select>
          </el-form-item>

          <el-form-item label="选择规格 SKU" required>
            <el-select 
              v-model="pushForm.skuId" 
              placeholder="请选择规格" 
              style="width: 100%;"
              @change="handleSkuChange"
            >
              <el-option 
                v-for="s in currentSkus" 
                :key="s.id" 
                :label="`${s.skuCode} (${formatSpec(s.specData)}) - 原价￥${s.price} [库存:${s.stockQuantity}]`" 
                :value="s.id" 
              />
            </el-select>
          </el-form-item>

          <el-form-item label="购买数量" required>
            <el-input-number v-model="pushForm.quantity" :min="1" :max="999" />
          </el-form-item>

          <el-form-item label="协商协议单价">
            <el-input-number 
              v-model="pushForm.negotiatedPrice" 
              :precision="2" 
              :step="1" 
              :min="0.01"
              placeholder="留空则按原价" 
            />
            <span style="font-size: 11px; color: #64748b; margin-left: 10px;">(支持商户与老客协商自主打折或定制报价)</span>
          </el-form-item>

          <el-form-item label="客户联系方式">
            <el-input v-model="pushForm.customerContact" placeholder="客户手机号或微信（选填，用于客户查单）" />
          </el-form-item>

          <el-form-item label="商户定制留言">
            <el-input 
              v-model="pushForm.sellerNote" 
              type="textarea" 
              rows="2" 
              placeholder="例如：这是为您定制的专属特惠账单，请查收付款！" 
            />
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤 2: 推单生成成功展示 -->
      <div v-else style="text-align: center; padding: 10px 0;">
        <div style="font-size: 36px; margin-bottom: 8px;">🎉</div>
        <h3 style="margin: 0; color: #0f172a; font-size: 16px; font-weight: bold;">代客推单账单已生成！</h3>
        <p style="font-size: 12px; color: #64748b; margin: 4px 0 16px;">复制链接发给微信客户，或将海报发送至微信群/朋友圈</p>

        <el-alert type="warning" :closable="false" style="margin-bottom: 12px; text-align: left;">
          <p>订单号：{{ pushPayResult.orderSn }}</p>
          <p>买家查单时填写的联系标识：{{ pushPayResult.buyerContact }}</p>
          <p>买家领取凭证（只私密交给买家，不包含在付款分享中）：</p>
          <code style="user-select: all; word-break: break-all;">{{ pushPayResult.queryToken }}</code>
        </el-alert>
        <!-- 专属收银台链接与微信卡片复制 -->
        <div style="margin-bottom: 16px; display: flex; flex-direction: column; gap: 8px;">
          <el-button type="success" size="default" @click="copyPushWechatCard" style="width: 100%; font-weight: bold;">
            🟢 复制微信推单卡片 (直接发微信客户)
          </el-button>
          <div style="display: flex; gap: 8px;">
            <el-input :value="pushPayResult.cashierUrl" readonly />
            <el-button type="primary" plain @click="copyPushLink">复制链接</el-button>
          </div>
        </div>

        <!-- 微信防封海报预览 -->
        <div v-if="pushPayResult.posterBase64" style="background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px;">
          <div style="font-size: 12px; font-weight: 600; color: #475569; margin-bottom: 8px;">9:16 微信裂变防封买单海报预览：</div>
          <img :src="pushPayResult.posterBase64" style="max-height: 280px; border-radius: 6px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1);" />
          <div style="margin-top: 10px;">
            <a 
              :href="pushPayResult.posterBase64" 
              download="商户推单海报.png"
              style="display: inline-block; padding: 6px 16px; background: #e11d48; color: white; border-radius: 6px; font-size: 12px; text-decoration: none; font-weight: bold;"
            >
              下载保存海报图片
            </a>
          </div>
        </div>
      </div>

      <template #footer>
        <div v-if="!pushPayResult">
          <el-button @click="pushPayVisible = false">取消</el-button>
          <el-button type="danger" :loading="pushing" @click="handleCreatePushPay">一键生成推单账单与海报</el-button>
        </div>
        <div v-else>
          <el-button type="primary" @click="pushPayVisible = false">完成</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 4. 查看代付海报弹窗 -->
    <el-dialog v-model="posterDialogVisible" title="9:16 微信防封买单海报" width="460px">
      <div style="text-align: center;">
        <img :src="currentPosterUrl" style="max-height: 480px; width: auto; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.1);" />
        <div style="margin-top: 14px;">
          <a 
            :href="currentPosterUrl" 
            download="代付买单海报.png"
            style="display: inline-block; padding: 8px 20px; background: #e11d48; color: white; border-radius: 8px; font-size: 13px; text-decoration: none; font-weight: bold;"
          >
            下载海报图片
          </a>
        </div>
      </div>
    </el-dialog>

  </div>
</template>

<script setup>
import { createIdempotentSubmission } from '../../utils/idempotency.mjs'
const pushSubmission = createIdempotentSubmission()
import { canFulfillService } from '../../utils/goodsType.mjs'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'

const loading = ref(false)
const orders = ref([])
const deliverVisible = ref(false)
const virtualFulfillVisible = ref(false)
const pushPayVisible = ref(false)
const posterDialogVisible = ref(false)
const currentOrder = ref(null)
const currentPosterUrl = ref('')

const deliverForm = ref({
  logisticsCompany: '顺丰速运',
  trackingNumber: ''
})

// 线上服务凭证
const virtualVoucherText = ref('')
const fulfilling = ref(false)

// 代客推单状态
const pushing = ref(false)
const myProducts = ref([])
const currentSkus = ref([])
const pushPayResult = ref(null)
const pushForm = ref({
  productId: null,
  skuId: null,
  quantity: 1,
  negotiatedPrice: null,
  customerContact: '',
  sellerNote: '这是商家为您定制的支付账单，请查收付款！'
})

const getStatusTag = (status) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'PAID': return 'warning'
    case 'SHIPPED': return 'primary'
    default: return 'info'
  }
}

const formatSpec = (jsonStr) => {
  if (!jsonStr) return '标准规格'
  try {
    const obj = JSON.parse(jsonStr)
    return Object.entries(obj).map(([k, v]) => `${v}`).join(' / ')
  } catch (e) {
    return jsonStr
  }
}

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await request.get('/merchant/orders')
    orders.value = res.data
  } finally {
    loading.value = false
  }
}

const openDeliverDialog = (order) => {
  currentOrder.value = order
  deliverForm.value.trackingNumber = 'SF' + Math.floor(100000000000 + Math.random() * 900000000000)
  deliverVisible.value = true
}

const handleDeliver = async () => {
  if (!deliverForm.value.trackingNumber) {
    ElMessage.warning('请输入快递单号')
    return
  }
  await request.post(`/merchant/orders/${currentOrder.value.id}/deliver`, deliverForm.value)
  ElMessage.success('实物订单发货成功！')
  deliverVisible.value = false
  loadOrders()
}

// 线上服务交付
const openVirtualFulfillDialog = (order) => {
  currentOrder.value = order
  virtualVoucherText.value = ''
  virtualFulfillVisible.value = true
}

const handleVirtualFulfill = async () => {
  if (!virtualVoucherText.value.trim()) {
    ElMessage.warning('请填写线上服务交付凭据内容')
    return
  }
  fulfilling.value = true
  try {
    await request.post(`/merchant/orders/${currentOrder.value.id}/fulfill-virtual`, {
      serviceVoucher: virtualVoucherText.value.trim()
    })
    ElMessage.success('线上服务交付凭证已成功录入，订单已完结！')
    virtualFulfillVisible.value = false
    loadOrders()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '凭证录入失败')
  } finally {
    fulfilling.value = false
  }
}

// 代客推单逻辑
const openPushPayDialog = async () => {
  pushPayResult.value = null
  pushForm.value = {
    productId: null,
    skuId: null,
    quantity: 1,
    negotiatedPrice: null,
    customerContact: '',
    sellerNote: '这是商家为您定制的特惠支付账单，请查收付款！'
  }
  pushPayVisible.value = true

  // 加载商家商品
  try {
    const res = await request.get('/merchant/products')
    myProducts.value = res.data
    if (myProducts.value.length > 0) {
      pushForm.value.productId = myProducts.value[0].product.id
      handleProductChange(pushForm.value.productId)
    }
  } catch (e) {
    ElMessage.error('无法加载店铺商品')
  }
}

const handleProductChange = (prodId) => {
  const found = myProducts.value.find(p => p.product.id === prodId)
  if (found && found.skus && found.skus.length > 0) {
    currentSkus.value = found.skus
    pushForm.value.skuId = currentSkus.value[0].id
    pushForm.value.negotiatedPrice = currentSkus.value[0].price
  } else {
    currentSkus.value = []
    pushForm.value.skuId = null
    pushForm.value.negotiatedPrice = null
  }
}

const handleSkuChange = (skuId) => {
  const sku = currentSkus.value.find(s => s.id === skuId)
  if (sku) {
    pushForm.value.negotiatedPrice = sku.price
  }
}

const handleCreatePushPay = async () => {
  if (!pushForm.value.productId || !pushForm.value.skuId) {
    ElMessage.warning('请选择推单商品与SKU规格')
    return
  }
  pushing.value = true
  try {
    const prepared = pushSubmission.prepare(pushForm.value)
    const res = await request.post('/merchant/orders/push-pay', prepared.body, { headers: prepared.headers })
    pushSubmission.complete()
    pushPayResult.value = res.data
    ElMessage.success('代客推单账单生成成功！')
    loadOrders()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '推单失败')
  } finally {
    pushing.value = false
  }
}

const safeCopy = (text, successMsg) => {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => {
      ElMessage.success(successMsg)
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
    ElMessage.success(successMsg)
  } catch (e) {
    ElMessage.error('复制失败，请手动长按复制')
  }
  document.body.removeChild(ta)
}

const copyPushWechatCard = () => {
  if (!pushPayResult.value) return
  const text = pushPayResult.value.wechatShareText || 
    `【商户专属定制账单】\n尊贵的客户您好，这是为您定制的专属订单，请查收：\n💰 协议专享价：￥${pushPayResult.value.payAmount}\n👉 微信点开直接付款：${pushPayResult.value.cashierUrl}\n（微信内直接打开即可完成付款，无需加购选规格）`
  safeCopy(text, '✅ 微信专属推单卡片已复制！直接粘贴发送给微信客户即可，客户点开直接付款。')
}

const copyPushLink = () => {
  if (pushPayResult.value?.cashierUrl) {
    safeCopy(pushPayResult.value.cashierUrl, '推单代付短链已复制到剪贴板！')
  }
}

const viewPoster = (order) => {
  currentPosterUrl.value = order.posterUrl
  posterDialogVisible.value = true
}

onMounted(() => {
  loadOrders()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #0f172a;
}

.page-header p {
  margin: 4px 0 0;
  font-size: 13px;
  color: #64748b;
}

.table-card {
  border-radius: 8px;
}

.card-tag {
  margin-top: 4px;
}

.card-text {
  font-family: monospace;
  font-size: 11px;
  background: #fef3c7;
  color: #92400e;
  padding: 2px 6px;
  border-radius: 4px;
  margin-left: 6px;
}

.voucher-box {
  font-size: 11px;
  color: #15803d;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  padding: 4px 8px;
  border-radius: 4px;
  margin-top: 3px;
  word-break: break-all;
}

.peer-msg-box {
  font-size: 11px;
  color: #b45309;
  background: #fffbeb;
  padding: 3px 6px;
  border-radius: 4px;
  margin-top: 4px;
  line-height: 1.3;
}
</style>

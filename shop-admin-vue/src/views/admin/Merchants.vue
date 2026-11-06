<!--
文件：Merchants.vue
用途：平台商户资料、佣金、服务费、营业状态和收款模式配置页面。
维护注意：进件仍有模拟数据，不能把成功提示当真实渠道开通；后台校验为权威；服务费五种编码分别展示，未知值须明确选择，配置不代表扣费或停服；日期按LocalDate提交。G31：调价与开店初始费率必须提供复核人入口，候选排除当前账号；无第二账号时明确提示“需至少分配两个财务账号”，前端不绕过后端双人复核。O6：打烊/开业仅营业(1)/打烊(2)走旧开关；冻结/解封/关店/注销统一走 /store-status（原因码必填、解封双人、终态警示），状态审计只读时间线来自 /store-status-audits；动作/原因码规则集中在 utils/storeStatusActions.mjs，与后端字典同步维护。
-->
<template>
  <div class="merchants-page">
    <div class="page-header">
      <div>
        <h2>入驻商户治理与服务费监管</h2>
        <p>个人自然人免照开店、SaaS月度技术服务费阶梯配置与合规结算通道调配</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openDialog">新增/审批开通商户</el-button>
    </div>

    <el-alert v-if="reviewerPolicy.singleAdminMode" :title="reviewerPolicy.singleAdminNotice"
      type="warning" :closable="false" show-icon class="policy-alert" />

    <el-card shadow="never" class="table-card">
      <el-table :data="merchants" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="storeCode" label="编码" width="100" />
        <el-table-column prop="storeName" label="店铺名称 / 公告" min-width="170">
          <template #default="{ row }">
            <div style="font-weight: 600; color: #1e293b;">{{ row.storeName }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.notice || '暂无公告' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="商户性质" width="120">
          <template #default="{ row }">
            <el-tag :type="row.merchantType === 1 ? 'success' : 'primary'" size="small">
              {{ row.merchantType === 1 ? '自然人(免执照)' : '企业/个体' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="经营人/电话" width="140">
          <template #default="{ row }">
            <div style="font-weight: 500;">{{ row.ownerName }}</div>
            <div style="font-size: 11px; color: #64748b;">{{ row.contactPhone }}</div>
          </template>
        </el-table-column>

        <!-- ★ 月度技术服务费 (SaaS合规商业支柱) -->
        <el-table-column label="SaaS月技术服务费" width="160">
          <template #default="{ row }">
            <div>
              <span style="font-weight: 700; color: #0f172a;">￥{{ row.monthlyFee || 0 }}</span>
              <span style="font-size: 11px; color: #64748b;"> / 月</span>
            </div>
            <div style="margin-top: 2px;">
              <el-tag :type="feeStatusView(row.feeStatus).type" size="small" effect="plain">
                {{ feeStatusView(row.feeStatus).label }}
              </el-tag>
            </div>
            <div v-if="row.feeFreeUntil" style="font-size: 10px; color: #94a3b8; margin-top: 2px;">
              免费截止: {{ row.feeFreeUntil }}
            </div>
          </template>
        </el-table-column>

        <!-- ★ 结算收款通道模式 -->
        <el-table-column label="收款结算模式" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.paymentChannelType === 'A'" type="primary" size="small">
              A-电商收付通
            </el-tag>
            <el-tag v-else-if="row.paymentChannelType === 'B'" type="warning" size="small">
              B-商户直收码
            </el-tag>
            <el-tag v-else type="info" size="small">
              C-沙箱测试桩
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="commissionRate" label="抽佣比例" width="90">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">{{ row.commissionRate }}%</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="营业状态" width="95">
          <template #default="{ row }">
            <el-tag :type="storeStatusTagType(row.status)" size="small">
              {{ storeDisplay(row).status }}
            </el-tag>
          </template>
        </el-table-column>

        <!-- 管理操作 -->
        <el-table-column label="管理操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openFeeDialog(row)">配置服务费</el-button>
            <el-button size="small" type="warning" link @click="openChannelDialog(row)">切换通道</el-button>
            <el-button size="small" type="info" link @click="editCommission(row)">费率</el-button>
            <!-- O6：打烊/恢复仅营业(1)/打烊(2)可走旧开关；冻结(3)/注销(4)状态下该开关无合法动作 -->
            <el-button v-if="row.status === 1 || row.status === 2" size="small"
              :type="row.status === 1 ? 'danger' : 'success'" link @click="toggleStatus(row)">
              {{ row.status === 1 ? '打烊' : '开业' }}
            </el-button>
            <el-button v-if="statusActionAvailable(row)" size="small" type="danger" link
              @click="openStatusActionDialog(row)">冻结/解封/注销</el-button>
            <el-button size="small" type="info" link @click="openAuditDrawer(row)">状态审计</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 1. 新增商户弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增/开通入驻商户 (个人免执照模式)" width="540px" @closed="resetCreateForm">
      <el-form :model="form" label-width="120px">
        <el-form-item label="店铺唯一编码" required>
          <el-input v-model="form.storeCode" placeholder="如 STORE002" />
        </el-form-item>
        <el-form-item label="店铺名称" required>
          <el-input v-model="form.storeName" placeholder="如 极客数码特惠店" />
        </el-form-item>
        <el-form-item label="商户类型">
          <el-radio-group v-model="form.merchantType">
            <el-radio :label="1">个人自然人 (小微免执照)</el-radio>
            <el-radio :label="2">个体工商户/企业</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="经营者姓名" required>
          <el-input v-model="form.ownerName" placeholder="必须与银行卡户名一致" />
        </el-form-item>
        <el-form-item label="身份证号" required>
          <el-input v-model="form.idCardNumber" placeholder="18位居民身份证号码" />
        </el-form-item>
        <el-form-item label="联系手机号" required>
          <el-input v-model="form.contactPhone" placeholder="商户通知手机" />
        </el-form-item>
        <el-divider content-position="left">二级分账银行结算卡</el-divider>
        <el-form-item label="开户银行">
          <el-input v-model="form.bankName" placeholder="如 中国工商银行北京分行" />
        </el-form-item>
        <el-form-item label="银行借记卡号">
          <el-input v-model="form.bankAccountNumber" placeholder="6222 开头个人一类银行卡号" />
        </el-form-item>
        <el-divider content-position="left">工作台管理账号与费率</el-divider>
        <el-form-item label="店长登录账号" required>
          <el-input v-model="form.adminUsername" placeholder="如 seller02" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="form.adminPassword" placeholder="自动生成 16 位强口令，可修改" show-password />
          <el-button link type="primary" size="small" @click="refreshPassword">重新生成强口令</el-button>
          <div style="font-size: 11px; color: #64748b;">请通过安全渠道交付店长，首次登录后要求修改；不得使用 admin123 等固定口令。</div>
        </el-form-item>
        <el-form-item label="技术抽成比例(%)">
          <el-input-number v-model="form.commissionRate" :precision="2" :step="0.5" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="初始费率复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="form.rateConfirmedBy" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="review-hint">复核人候选已排除当前登录账号；开关打开时必填。</div>
        </el-form-item>
        <el-alert v-if="reviewerPolicy.notice" :title="reviewerPolicy.notice" type="warning"
          :closable="false" show-icon class="review-alert" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" :disabled="reviewerPolicy.blocked"
          @click="handleCreate">确认开通并进件</el-button>
      </template>
    </el-dialog>

    <!-- 2. ★ 配置商户技术服务费弹窗 -->
    <el-dialog v-model="feeDialogVisible" title="配置商户月度技术服务费 (SaaS合规模式)" width="480px">
      <el-form label-width="120px">
        <el-form-item label="月技术服务费">
          <el-input-number v-model="feeForm.monthlyFee" :precision="2" :step="10" :min="0" />
          <span style="margin-left: 10px; font-size: 12px; color: #64748b;">元 / 月</span>
        </el-form-item>
        <el-form-item label="免费免收截止日">
          <el-date-picker 
            v-model="feeForm.feeFreeUntil" 
            type="date" 
            placeholder="选择免收截止日期" 
            value-format="YYYY-MM-DD"
            style="width: 100%;" 
          />
        </el-form-item>
        <el-form-item label="服务费状态">
          <el-select v-model="feeForm.feeStatus" placeholder="请选择有效状态" style="width: 100%;">
            <el-option v-for="option in FEE_STATUS_OPTIONS" :key="option.value"
              :label="`${option.value} (${option.label})`" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="text-xs text-slate-500">这里只保存配置标记，不代表已扣费、豁免执行或自动停服；截止日留空会保留原值。</p>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="feeSaving" @click="handleSaveFee">保存服务费配置</el-button>
      </template>
    </el-dialog>

    <!-- 3. ★ 切换商户支付结算模式弹窗 -->
    <el-dialog v-model="channelDialogVisible" title="切换收款通道模式 (收付通 / 独立直收 / 测试沙箱)" width="500px">
      <el-form label-width="110px">
        <el-form-item label="结算通道类型">
          <el-radio-group v-model="channelForm.paymentChannelType">
            <el-radio-button label="C">方案C (沙箱测试桩)</el-radio-button>
            <el-radio-button label="B">方案B (商户直收码)</el-radio-button>
            <el-radio-button label="A">方案A (收付通合规分账)</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="channelForm.paymentChannelType === 'B'" label="商户收款二维码">
          <el-input v-model="channelForm.customPayQrcode" placeholder="请输入商户微信/支付宝个人收款码图片URL" />
          <div style="font-size: 11px; color: #e11d48; margin-top: 4px;">
            * 方案B模式下，付款人调起收银台将直接展示此收款码，资金直达商户，平台零二清接触。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="channelSaving" @click="handleSaveChannel">保存收款通道</el-button>
      </template>
    </el-dialog>

    <!-- 4. G31 费率调整弹窗（费率 + 原因 + 复核人） -->
    <el-dialog v-model="commissionDialogVisible" title="调整技术服务费率" width="480px">
      <el-form label-width="110px">
        <el-form-item label="新费率(%)">
          <el-input-number v-model="commissionForm.rate" :precision="2" :step="0.5" :min="0" :max="10" />
          <div class="review-hint">0% 视为推广费率；调高次日 00:00 生效，降费即时生效。</div>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="commissionForm.reason" maxlength="100"
            placeholder="可不填，后端按接口默认原因记录" />
        </el-form-item>
        <el-form-item label="复核人" :required="reviewerPolicy.dualApprovalRequired">
          <el-select v-model="commissionForm.confirmedBy" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="review-hint">复核人仅作参数传递，是否允许同人由后端双人复核开关判定。</div>
        </el-form-item>
        <el-alert v-if="reviewerPolicy.notice" :title="reviewerPolicy.notice" type="warning"
          :closable="false" show-icon class="review-alert" />
      </el-form>
      <template #footer>
        <el-button @click="commissionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="commissionSaving" :disabled="reviewerPolicy.blocked"
          @click="submitCommission">保存费率</el-button>
      </template>
    </el-dialog>

    <!-- 5. O6 店铺状态操作弹窗（冻结/解封/关店/注销，原因码 + 审计 + 解封双人复核） -->
    <el-dialog v-model="statusActionDialogVisible" title="店铺状态操作（全程审计留痕）" width="540px">
      <el-form label-width="110px">
        <el-form-item label="店铺">
          <span style="font-weight: 600;">{{ statusActionForm.storeName }}</span>
          <el-tag size="small" style="margin-left: 8px;" :type="storeStatusTagType(statusActionForm.status)">
            当前：{{ storeDisplay(statusActionForm).status }}
          </el-tag>
        </el-form-item>
        <el-form-item label="状态操作" required>
          <el-radio-group v-model="statusActionForm.action">
            <el-radio-button v-for="act in statusActionOptions" :key="act" :label="act">
              {{ STORE_ACTION_LABELS[act] }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原因码" required>
          <el-select v-model="statusActionForm.reasonCode" style="width: 100%" placeholder="必须选择字典内原因码">
            <el-option v-for="item in STORE_REASON_CODES" :key="item.value"
              :label="`${item.value}（${item.label}）`" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因说明" :required="statusActionForm.reasonCode === 'OTHER'">
          <el-input v-model="statusActionForm.reason" type="textarea" :rows="2" maxlength="512" show-word-limit
            placeholder="原因码为 OTHER 时必填；写入审计，不对商家展示操作人隐私" />
        </el-form-item>
        <el-form-item label="复核人" :required="statusActionReviewerRequired">
          <el-select v-model="statusActionForm.confirmedBy" clearable style="width: 100%"
            :placeholder="reviewerPlaceholder">
            <el-option v-for="item in reviewerPolicy.candidates" :key="item.id"
              :label="reviewerLabel(item)" :value="item.id" />
          </el-select>
          <div class="review-hint">解封按后端双人复核开关强制；关店/注销选填复核人时后端强制换人校验。</div>
        </el-form-item>
        <el-alert v-if="statusActionTerminal" type="error" :closable="false" show-icon
          title="关店/注销为终态操作：需无未完结订单、无处理中退款、无未确认批次或欠费、商品全部下架；成功后商家账号全部停用，不可恢复。" />
        <el-alert v-else-if="statusActionForm.action === 'UNFREEZE'" type="warning" :closable="false" show-icon
          title="解封后店铺恢复营业并可正常交易结算；操作与复核人写入审计。" />
      </el-form>
      <template #footer>
        <el-button @click="statusActionDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="statusActionSaving" @click="submitStatusAction">确认执行</el-button>
      </template>
    </el-dialog>

    <!-- 6. O6 店铺状态审计时间线 -->
    <el-drawer v-model="auditDrawerVisible" :title="`店铺状态审计 - ${auditStoreName}`" size="480px">
      <el-skeleton v-if="auditLoading" :rows="4" animated />
      <el-timeline v-else-if="auditItems.length" style="padding-left: 4px;">
        <el-timeline-item v-for="item in auditItems" :key="item.id" :timestamp="item.createdAt" placement="top">
          <div style="font-weight: 600;">{{ item.view.actionLabel }}</div>
          <div class="audit-line">原因码：{{ item.view.reasonLabel || '—' }}</div>
          <div class="audit-line">
            {{ item.view.operatorLabel }}<template v-if="item.view.confirmedByLabel"> · {{ item.view.confirmedByLabel }}</template> · {{ item.view.approvalLabel }}
          </div>
          <div v-if="item.reason" class="audit-line">说明：{{ item.reason }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无状态审计记录" />
    </el-drawer>

  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { FEE_STATUS_OPTIONS, feeStatusView, serviceFeeForm, serviceFeePayload } from '../../utils/serviceFee.mjs'
import { storeDisplay } from '../../utils/orderStatistics.mjs'
import { createIdempotentSubmission } from '../../utils/idempotency.mjs'
import { generateTemporaryPassword, isStrongPassword } from '../../utils/adminAccount.mjs'
import { reviewerState, validateReviewer } from '../../utils/financeReview.mjs'
import {
  STORE_ACTION_LABELS,
  STORE_REASON_CODES,
  auditView,
  availableStatusActions,
  requiresDualApproval,
  validateStoreStatusAction
} from '../../utils/storeStatusActions.mjs'

const createSubmission = createIdempotentSubmission()
const loading = ref(false)
const submitLoading = ref(false)
const merchants = ref([])
const dialogVisible = ref(false)

// G31：复核人候选由后端按 SUPERADMIN + 启用 + 排除当前账号返回，页面不做角色猜测。
const currentUserId = localStorage.getItem('userId') || ''
const reviewers = ref([])
const dualApprovalRequired = ref(false)
const reviewerPolicy = computed(() => reviewerState({
  dualApprovalRequired: dualApprovalRequired.value,
  reviewers: reviewers.value,
  currentUserId
}))
const reviewerPlaceholder = computed(() => reviewerPolicy.value.dualApprovalRequired
  ? '必须选择复核人'
  : '可留空，按当前操作人记录同人复核')
const reviewerLabel = item => `${item.realName || item.username || '管理员'}（${item.username}）`

const loadReviewers = async () => {
  try {
    const res = await request.get('/admin/settlement/reviewers', { skipErrorToast: true })
    dualApprovalRequired.value = Boolean(res.data?.dualApprovalRequired)
    reviewers.value = Array.isArray(res.data?.reviewers) ? res.data.reviewers : []
  } catch (error) {
    reviewers.value = []
    ElMessage.warning(error.response?.data?.message || '复核人候选加载失败，请刷新后重试')
  }
}

const guardReviewer = reviewerId => {
  const result = validateReviewer({
    dualApprovalRequired: dualApprovalRequired.value,
    reviewers: reviewers.value,
    currentUserId,
    reviewerId
  })
  if (!result.ok) {
    ElMessage.warning(result.message)
    return null
  }
  return result
}

const form = ref({
  storeCode: '',
  storeName: '',
  merchantType: 1,
  ownerName: '',
  idCardNumber: '',
  contactPhone: '',
  bankAccountNumber: '',
  bankName: '',
  adminUsername: '',
  adminPassword: '',
  commissionRate: 3.00,
  rateConfirmedBy: ''
})

const storeStatusTagType = status => {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  if (status === 3) return 'danger'
  if (status === 4) return 'info'
  return 'info'
}

const refreshPassword = () => {
  form.value.adminPassword = generateTemporaryPassword(16)
}

const resetCreateForm = () => {
  form.value = {
    storeCode: '',
    storeName: '',
    merchantType: 1,
    ownerName: '',
    idCardNumber: '',
    contactPhone: '',
    bankAccountNumber: '',
    bankName: '',
    adminUsername: '',
    adminPassword: '',
    commissionRate: 3.00,
    rateConfirmedBy: ''
  }
  refreshPassword()
}

// 服务费配置
const feeDialogVisible = ref(false)
const feeSaving = ref(false)
const currentStoreId = ref(null)
const feeForm = ref({
  monthlyFee: 0,
  feeFreeUntil: '',
  feeStatus: 'NORMAL'
})

// 通道模式配置
const channelDialogVisible = ref(false)
const channelSaving = ref(false)
const channelForm = ref({
  paymentChannelType: 'C',
  customPayQrcode: ''
})

// G31 费率调整（费率 + 原因 + 复核人）
const commissionDialogVisible = ref(false)
const commissionSaving = ref(false)
const commissionForm = ref({
  storeId: null,
  rate: 3,
  reason: '',
  confirmedBy: ''
})

// O6 店铺状态操作（冻结/解封/关店/注销：原因码 + 审计 + 解封双人复核）
const statusActionDialogVisible = ref(false)
const statusActionSaving = ref(false)
const statusActionOptions = ref([])
const statusActionForm = ref({
  storeId: null,
  storeName: '',
  status: null,
  action: '',
  reasonCode: '',
  reason: '',
  confirmedBy: ''
})
const statusActionTerminal = computed(() =>
  statusActionForm.value.action === 'CLOSE' || statusActionForm.value.action === 'CANCEL')
// 解封的双人强制跟随后端开关；关店/注销复核人选填（选了后端强制换人校验）。
const statusActionReviewerRequired = computed(() =>
  requiresDualApproval(statusActionForm.value.action) && reviewerPolicy.value.dualApprovalRequired)
const statusActionAvailable = row => availableStatusActions(row?.status).length > 0

// O6 状态审计时间线
const auditDrawerVisible = ref(false)
const auditLoading = ref(false)
const auditStoreName = ref('')
const auditItems = ref([])

const loadMerchants = async () => {
  loading.value = true
  try {
    const res = await request.get('/admin/merchants')
    merchants.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '商户列表加载失败')
  } finally {
    loading.value = false
  }
}

const openDialog = () => {
  resetCreateForm()
  form.value.storeCode = 'STORE' + Math.floor(100 + Math.random() * 900)
  dialogVisible.value = true
}

const handleCreate = async () => {
  if (submitLoading.value) return
  if (!form.value.storeCode.trim() || !form.value.storeName.trim() || !form.value.ownerName.trim()
    || !form.value.idCardNumber.trim() || !form.value.contactPhone.trim() || !form.value.adminUsername.trim()) {
    ElMessage.warning('请填写完整的商户与进件必填项')
    return
  }
  if (!isStrongPassword(form.value.adminPassword)) {
    ElMessage.warning('初始密码至少 8 位且包含字母与数字，请重新生成强口令')
    return
  }
  const selection = guardReviewer(form.value.rateConfirmedBy)
  if (!selection) return
  const payload = { ...form.value }
  if (selection.confirmedBy === null) delete payload.rateConfirmedBy
  else payload.rateConfirmedBy = selection.confirmedBy
  submitLoading.value = true
  const prepared = createSubmission.prepare(payload)
  try {
    await request.post('/admin/merchants', prepared.body, { headers: prepared.headers })
    createSubmission.complete()
    ElMessage.success('商户已开通（演示进件通道）')
    dialogVisible.value = false
    loadMerchants()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '商户开通失败，请检查必填项后重试')
  } finally {
    submitLoading.value = false
  }
}

const toggleStatus = async (row) => {
  const newStatus = row.status === 1 ? 2 : 1
  try {
    await request.put(`/admin/merchants/${row.id}/status?status=${newStatus}`)
    ElMessage.success('店铺状态已切换')
    loadMerchants()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '店铺状态切换失败')
  }
}

const editCommission = (row) => {
  commissionForm.value = {
    storeId: row.id,
    rate: Number(row.commissionRate ?? 3),
    reason: '',
    confirmedBy: ''
  }
  commissionDialogVisible.value = true
}

const submitCommission = async () => {
  if (commissionSaving.value) return
  const selection = guardReviewer(commissionForm.value.confirmedBy)
  if (!selection) return
  const params = { rate: commissionForm.value.rate }
  if (commissionForm.value.reason && commissionForm.value.reason.trim()) {
    params.reason = commissionForm.value.reason.trim()
  }
  if (selection.confirmedBy !== null) params.confirmedBy = selection.confirmedBy
  commissionSaving.value = true
  try {
    await request.put(`/admin/merchants/${commissionForm.value.storeId}/commission`, null, { params })
    ElMessage.success('费率调整成功')
    commissionDialogVisible.value = false
    loadMerchants()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '费率调整失败')
  } finally {
    commissionSaving.value = false
  }
}

// 打开服务费弹窗
const openFeeDialog = (row) => {
  currentStoreId.value = row.id
  feeForm.value = serviceFeeForm(row)
  feeDialogVisible.value = true
}

const handleSaveFee = async () => {
  feeSaving.value = true
  try {
    await request.put(`/admin/merchants/${currentStoreId.value}/service-fee`, serviceFeePayload(feeForm.value))
    ElMessage.success('商户技术服务费配置已保存！')
    feeDialogVisible.value = false
    loadMerchants()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '配置保存失败')
  } finally {
    feeSaving.value = false
  }
}

// 打开通道弹窗
const openChannelDialog = (row) => {
  currentStoreId.value = row.id
  channelForm.value = {
    paymentChannelType: row.paymentChannelType || 'C',
    customPayQrcode: row.customPayQrcode || ''
  }
  channelDialogVisible.value = true
}

// O6 打开状态操作弹窗：动作选项由当前状态决定（营业/打烊→冻结/关店/注销；冻结→解封）
const openStatusActionDialog = (row) => {
  const options = availableStatusActions(row.status)
  if (options.length === 0) {
    ElMessage.warning('当前状态无可执行的状态操作')
    return
  }
  statusActionForm.value = {
    storeId: row.id,
    storeName: row.storeName,
    status: row.status,
    action: options[0],
    reasonCode: '',
    reason: '',
    confirmedBy: ''
  }
  statusActionOptions.value = options
  statusActionDialogVisible.value = true
}

// O6 提交状态操作：前端只做初筛，状态迁移/原因码/双人复核以后端为准
const submitStatusAction = async () => {
  if (statusActionSaving.value) return
  const verdict = validateStoreStatusAction({
    action: statusActionForm.value.action,
    reasonCode: statusActionForm.value.reasonCode,
    reason: statusActionForm.value.reason
  })
  if (!verdict.ok) {
    ElMessage.warning(verdict.message)
    return
  }
  let confirmedBy = null
  const chosen = statusActionForm.value.confirmedBy
  const reviewerChosen = chosen !== '' && chosen !== null && chosen !== undefined
  if (requiresDualApproval(verdict.payload.action) || reviewerChosen) {
    const selection = guardReviewer(chosen)
    if (!selection) return
    confirmedBy = selection.confirmedBy
  }
  const body = { ...verdict.payload }
  if (body.reason === null) delete body.reason
  if (confirmedBy !== null) body.confirmedBy = confirmedBy
  statusActionSaving.value = true
  try {
    await request.post(`/admin/merchants/${statusActionForm.value.storeId}/store-status`, body)
    ElMessage.success('店铺状态操作已完成并写入审计')
    statusActionDialogVisible.value = false
    loadMerchants()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '店铺状态操作失败')
  } finally {
    statusActionSaving.value = false
  }
}

// O6 状态审计时间线：只读展示，动作/原因码/复核方式由共享规则模块翻译
const openAuditDrawer = async (row) => {
  auditStoreName.value = row.storeName || ''
  auditItems.value = []
  auditDrawerVisible.value = true
  auditLoading.value = true
  try {
    const res = await request.get(`/admin/merchants/${row.id}/store-status-audits`)
    auditItems.value = (Array.isArray(res.data) ? res.data : [])
      .map(item => ({ ...item, view: auditView(item) }))
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '状态审计加载失败')
  } finally {
    auditLoading.value = false
  }
}

const handleSaveChannel = async () => {
  channelSaving.value = true
  try {
    await request.put(`/admin/merchants/${currentStoreId.value}/payment-channel`, channelForm.value)
    ElMessage.success('结算收款通道模式已更新！')
    channelDialogVisible.value = false
    loadMerchants()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '通道更新失败')
  } finally {
    channelSaving.value = false
  }
}

onMounted(() => {
  loadMerchants()
  loadReviewers()
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

.review-hint {
  width: 100%;
  font-size: 11px;
  color: #64748b;
  line-height: 1.5;
}

.review-alert {
  margin: 4px 0 8px;
}

.audit-line {
  font-size: 12px;
  color: #64748b;
  line-height: 1.6;
}
</style>

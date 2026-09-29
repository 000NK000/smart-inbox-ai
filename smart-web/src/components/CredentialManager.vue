<template>
  <section class="credentials-page">
    <div class="intro">
      <span>LOCAL VAULT</span>
      <h2>{{ t('账号与密钥') }}</h2>
      <p>{{ t('所有值默认隐藏并加密保存在本机。查看或替换时需要输入管理密码。') }}</p>
    </div>

    <div class="outlook-card">
      <div>
        <span class="provider">MICROSOFT 365</span>
        <h3>Outlook · {{ outlook.account || 'mailbox@example.com' }}</h3>
        <p v-if="outlook.connected && outlook.mode === 'desktop'">{{ t('已连接本机 Outlook，系统每两分钟读取一次最近的未读邮件。') }}</p>
        <p v-else-if="outlook.connected">{{ t('Microsoft OAuth 已连接，系统每两分钟读取一次最近的未读邮件。') }}</p>
        <p v-else-if="outlook.desktopState === 'no_profile'">{{ t('Waterloo 已阻止 API 授权。可在 Outlook 网页创建重定向规则，系统会把转来的邮件归入 Outlook 标签。') }}</p>
        <p v-else-if="outlook.desktopState === 'account_not_found'">{{ t('本机 Outlook 已配置，但没有找到这个 Waterloo 账号。') }}</p>
        <p v-else-if="outlook.configured">{{ t('Client ID 已配置。点击连接后，在微软页面使用 Waterloo 账号登录。') }}</p>
        <p v-else>{{ t('可以直接连接本机经典 Outlook；Client ID 仅作为管理员开放 Entra 注册后的备用方案。') }}</p>
      </div>
      <div class="outlook-actions">
        <el-tag :type="outlook.connected ? 'success' : 'warning'">{{ outlook.connected ? t('已连接') : outlook.desktopState === 'no_profile' ? t('等待转发') : t('未连接') }}</el-tag>
        <el-button type="primary" :loading="outlookStarting || desktopDetecting" @click="outlook.desktopState === 'no_profile' && !outlook.configured ? forwardingDialog = true : outlook.configured ? startOutlook() : detectDesktop()">
          {{ outlook.connected ? t('重新检测') : outlook.desktopState === 'no_profile' && !outlook.configured ? t('查看转发步骤') : outlook.configured ? t('连接 Microsoft') : t('检测本机 Outlook') }}
        </el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" :title="t('管理授权会话只保留在当前页面内存中，并在 5 分钟后失效。')" />
    <el-table :data="credentials" v-loading="loading" style="margin-top: 18px">
      <el-table-column prop="category" :label="t('类型')" width="150" />
      <el-table-column prop="label" :label="t('账号或密钥')" min-width="240" />
      <el-table-column :label="t('值')" min-width="180"><template #default="{ row }"><span class="masked">{{ revealed[row.id] ?? row.maskedValue }}</span></template></el-table-column>
      <el-table-column :label="t('生效时间')" width="135"><template #default="{ row }"><el-tag :type="row.restartRequired ? 'warning' : 'success'">{{ row.restartRequired ? t('重启采集器') : t('下次采集') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('操作')" width="160"><template #default="{ row }"><el-button link type="primary" @click="reveal(row)">{{ t('查看') }}</el-button><el-button link type="primary" @click="edit(row)">{{ t('替换') }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="passwordDialog" :title="t('输入管理密码')" width="360px" :close-on-click-modal="false">
      <p>{{ t(pendingAction === 'reveal' ? '请先验证身份，再查看这项内容。' : '请先验证身份，再替换这项内容。') }}</p>
      <el-input v-model="password" type="password" show-password @keyup.enter="authorize" />
      <template #footer><el-button @click="passwordDialog=false">{{ t('取消') }}</el-button><el-button type="primary" :loading="authorizing" @click="authorize">{{ t('继续') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="editDialog" :title="t('替换账号或密钥')" width="480px" :close-on-click-modal="false">
      <p>{{ selected?.label }}{{ t('。这个值不会保存在浏览器中。') }}</p>
      <el-input v-model="newValue" type="password" show-password autocomplete="new-password" />
      <template #footer><el-button @click="editDialog=false">{{ t('取消') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ t('加密保存') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="outlookDialog" :title="t('连接 Waterloo Microsoft 365')" width="520px" :close-on-click-modal="false">
      <div class="device-flow">
        <p>{{ t('打开微软登录页面，使用') }} <strong>{{ outlook.account }}</strong> {{ t('登录，然后输入下面的代码：') }}</p>
        <code>{{ device.userCode }}</code>
        <a :href="device.verificationUri" target="_blank" rel="noopener noreferrer">{{ t('打开 Microsoft 登录页面 ↗') }}</a>
        <small>{{ t(deviceStateText) }}</small>
      </div>
    </el-dialog>

    <el-dialog v-model="forwardingDialog" :title="t('通过 Outlook 网页接入')" width="560px">
      <ol class="forward-steps">
        <li>{{ t('在 Outlook 网页右上角打开“设置”。') }}</li>
        <li>{{ t('进入“邮件 → 规则”，点击“添加新规则”。') }}</li>
        <li>{{ t('规则名称填写') }} <strong>Smart Inbox</strong>。</li>
        <li>{{ t('条件选择“应用于所有邮件”。') }}</li>
        <li>{{ t('操作选择“重定向到”，填写') }} <strong>mailbox@example.com</strong>。</li>
        <li>{{ t('不要添加删除操作，点击“保存”。Outlook 原邮箱仍会保留邮件副本。') }}</li>
      </ol>
      <el-alert type="warning" :closable="false" :title="t('启用后，Waterloo 新邮件会复制到你的 Cornell Gmail；请确认这符合你对学校邮件隐私的要求。')" />
      <template #footer><el-button @click="forwardingDialog=false">{{ t('关闭') }}</el-button><a class="guide-link" href="https://uwaterloo.ca/science-computing/how-tos/email" target="_blank" rel="noopener noreferrer">{{ t('Waterloo 官方说明 ↗') }}</a></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { ElTag, ElButton, ElAlert, ElTable, ElTableColumn, ElDialog, ElInput, vLoading } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const credentials = ref([])
const loading = ref(false)
const revealed = ref({})
const passwordDialog = ref(false)
const editDialog = ref(false)
const password = ref('')
const selected = ref(null)
const pendingAction = ref('reveal')
const sessionToken = ref(null)
const authorizing = ref(false)
const saving = ref(false)
const newValue = ref('')
const outlook = ref({ configured: false, connected: false, account: 'mailbox@example.com' })
const outlookStarting = ref(false)
const desktopDetecting = ref(false)
const outlookDialog = ref(false)
const forwardingDialog = ref(false)
const device = ref({})
const deviceState = ref('pending')
let pollTimer

const deviceStateText = computed(() => ({
  pending: '正在等待微软授权…',
  connected: '连接成功，正在读取 Outlook 邮件。',
  failed: '连接失败，请关闭窗口后重试。',
  expired: '登录代码已过期，请关闭窗口后重新连接。'
}[deviceState.value] || '正在等待微软授权…'))

const load = async () => {
  loading.value = true
  try { credentials.value = (await axios.get('/api/credentials')).data }
  catch { ElMessage.error(t('无法加载密钥，请确认采集服务正在运行。')) }
  finally { loading.value = false }
}
const loadOutlookStatus = async () => {
  try { outlook.value = (await axios.get('/api/outlook/status')).data } catch { /* collector may still be starting */ }
}
const start = (row, action) => {
  selected.value = row
  pendingAction.value = action
  password.value = ''
  if (sessionToken.value) return action === 'reveal' ? doReveal() : (editDialog.value = true)
  passwordDialog.value = true
}
const reveal = row => start(row, 'reveal')
const edit = row => start(row, 'replace')
const authorize = async () => {
  authorizing.value = true
  try {
    const { data } = await axios.post('/api/credentials/authorize', { password: password.value })
    sessionToken.value = data.sessionToken
    password.value = ''
    passwordDialog.value = false
    pendingAction.value === 'reveal' ? await doReveal() : (editDialog.value = true)
  } catch { ElMessage.error(t('管理密码不正确。')) }
  finally { authorizing.value = false }
}
const headers = () => ({ 'X-Credential-Session': sessionToken.value })
const doReveal = async () => {
  try { revealed.value[selected.value.id] = (await axios.get(`/api/credentials/${selected.value.id}/reveal`, { headers: headers() })).data.value }
  catch { sessionToken.value = null; ElMessage.error(t('授权已过期，请重新输入管理密码。')) }
}
const save = async () => {
  if (!newValue.value) return ElMessage.warning(t('请输入新的值。'))
  saving.value = true
  try {
    const { data } = await axios.put(`/api/credentials/${selected.value.id}`, { value: newValue.value }, { headers: headers() })
    newValue.value = ''
    editDialog.value = false
    revealed.value[selected.value.id] = '••••••••'
    ElMessage.success(t(data.restartRequired ? '已加密保存，重启采集服务后生效。' : '已加密保存，下次采集时生效。'))
    await Promise.all([load(), loadOutlookStatus()])
  } catch { sessionToken.value = null; ElMessage.error(t('保存失败，授权可能已经过期。')) }
  finally { saving.value = false }
}

const startOutlook = async () => {
  outlookStarting.value = true
  try {
    device.value = (await axios.post('/api/outlook/device/start')).data
    deviceState.value = 'pending'
    outlookDialog.value = true
    clearInterval(pollTimer)
    pollTimer = setInterval(pollOutlook, Math.max(5, device.value.interval || 5) * 1000)
  } catch (error) { ElMessage.error(t(error.response?.data?.message || '无法开始 Microsoft 登录。')) }
  finally { outlookStarting.value = false }
}
const detectDesktop = async () => {
  desktopDetecting.value = true
  try {
    const { data } = await axios.post('/api/outlook/desktop/refresh')
    await loadOutlookStatus()
    if (data.state === 'connected') ElMessage.success(t('已经连接本机 Outlook，正在读取未读邮件。'))
    else if (data.state === 'no_profile') ElMessage.warning(t('请先打开经典 Outlook，并添加 Waterloo 邮箱。'))
    else if (data.state === 'account_not_found') ElMessage.warning(t('Outlook 中没有找到 mailbox@example.com。'))
    else ElMessage.error(t('暂时无法连接本机 Outlook。'))
  } catch { ElMessage.error(t('检测本机 Outlook 失败。')) }
  finally { desktopDetecting.value = false }
}
const pollOutlook = async () => {
  if (!device.value.sessionId || !outlookDialog.value) return
  try {
    const { data } = await axios.get('/api/outlook/device/poll', { params: { sessionId: device.value.sessionId } })
    deviceState.value = data.state
    if (data.state === 'connected') {
      clearInterval(pollTimer)
      await loadOutlookStatus()
      await axios.post('/api/outlook/refresh')
      ElMessage.success(t('Outlook 已连接，正在读取未读邮件。'))
    } else if (data.state === 'failed' || data.state === 'expired') {
      clearInterval(pollTimer)
      if (data.message) ElMessage.error(t(data.message))
    }
  } catch { clearInterval(pollTimer); deviceState.value = 'failed' }
}

onMounted(() => Promise.all([load(), loadOutlookStatus()]))
onBeforeUnmount(() => clearInterval(pollTimer))
</script>

<style scoped>
.credentials-page{max-width:1040px;margin:0 auto}.intro>span,.provider{font-size:12px;font-weight:800;letter-spacing:.16em;color:#5580bd}.intro h2{margin:7px 0 4px;font-size:32px}.intro p,.outlook-card p{color:#68778d;line-height:1.6}.outlook-card{display:flex;align-items:center;justify-content:space-between;gap:24px;margin:22px 0;padding:24px 28px;border-radius:24px;background:rgba(255,255,255,.84);box-shadow:0 14px 38px rgba(67,94,130,.11)}.outlook-card h3{margin:7px 0 2px;font-size:21px}.outlook-card p{margin:0}.outlook-actions{display:flex;align-items:center;gap:12px;flex-shrink:0}.masked{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.device-flow{display:grid;gap:18px;text-align:center}.device-flow code{display:block;padding:18px;border-radius:16px;background:#eef5ff;color:#1f64c7;font-size:30px;font-weight:800;letter-spacing:.18em}.device-flow a,.guide-link{display:inline-flex;justify-self:center;padding:11px 18px;border-radius:12px;background:#2874e8;color:white;text-decoration:none;font-weight:700}.device-flow small{color:#75849a}.forward-steps{display:grid;gap:10px;padding-left:24px;line-height:1.55;color:#445166}.guide-link{margin-left:10px;padding:8px 14px}@media(max-width:720px){.outlook-card{align-items:flex-start;flex-direction:column}.outlook-actions{width:100%;justify-content:space-between}}
</style>

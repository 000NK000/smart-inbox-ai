<template>
  <section class="mobile-connections" :aria-label="t('手机连接管理')" :aria-busy="loading || !!busy">
    <header class="mobile-intro">
      <div><span class="eyebrow">{{ t('桌面端 · 设备管理') }}</span><h2>{{ t('让手机接上你的工作台。') }}</h2><p>{{ t('在电脑上生成配对码，管理可以访问 Smart Inbox 的手机。') }}</p></div>
      <button type="button" class="quiet refresh-status" :disabled="loading || !!busy" @click="load">{{ loading ? t('正在刷新…') : t('刷新连接状态') }}</button>
    </header>

    <div v-if="error" class="notice error" role="alert"><span>{{ t(error) }}</span><button type="button" class="quiet retry-status" :disabled="loading || !!busy" @click="load">{{ t('刷新后重试') }}</button></div>
    <p v-if="notice" class="notice" role="status">{{ t(notice) }}</p>
    <p v-if="loading && !ready" class="empty" role="status">{{ t('正在读取手机连接状态…') }}</p>
    <p v-else-if="!ready" class="empty">{{ t('暂时无法显示连接状态，请刷新后重试。') }}</p>

    <template v-else>
      <div class="mobile-grid">
        <article class="connection-card">
          <div class="card-heading"><span class="phone-icon" aria-hidden="true">▯</span><div><h3>{{ t('手机访问地址') }}</h3><span class="connection-status" :class="{ connected: !needsSetup }">{{ !status.configured ? t('尚未配置') : needsSetup ? t('服务未运行') : t('可连接') }}</span></div></div>
          <p class="backend-message">{{ t(status.message || (!status.configured ? '手机访问尚未配置，配置完成后可在这里配对。' : needsSetup ? '手机连接服务当前未运行，请稍后刷新状态。' : '手机连接服务已就绪，可生成一次性配对码。')) }}</p>
          <div v-if="needsSetup || approvalUrl" class="network-setup">
            <p v-if="status.network?.message" class="network-message">{{ t(status.network.message) }}</p>
            <p v-if="status.network?.needsLogin || approvalUrl">{{ t('请在 Windows 和 iPhone 上使用同一个 Tailscale 账号登录。') }}</p>
            <button v-if="needsSetup" type="button" class="quiet setup-network" :disabled="loading || !!busy || !fresh" @click="setupNetwork">{{ busy === 'setup' ? t('正在设置网络…') : t('完成网络设置') }}</button>
            <a v-if="approvalUrl" class="approval-link" :href="approvalUrl" target="_blank" rel="noopener noreferrer">{{ t('前往 Tailscale 完成登录 ↗') }}</a>
            <p v-if="approvalUrl" class="fineprint">{{ t('完成登录后，刷新连接状态再生成配对码。') }}</p>
          </div>
          <div class="origin-box">
            <span>{{ t('在 iPhone 的 Safari 中打开') }}</span>
            <a v-if="origin" class="mobile-origin" :href="origin" target="_blank" rel="noopener noreferrer">{{ origin }} <span aria-hidden="true">↗</span></a>
            <span v-else class="unavailable-origin">{{ status.origin || t('地址尚未就绪') }}</span>
          </div>
          <p v-if="status.origin && !origin" class="origin-warning">{{ t('访问地址需要是有效的 HTTPS 地址，请检查电脑端配置。') }}</p>
          <p class="connection-note">{{ t('电脑需保持开机，Smart Inbox 服务需保持运行。') }}</p>
        </article>

        <article class="connection-card pairing-card">
          <div class="card-heading"><span class="pair-icon" aria-hidden="true">＋</span><div><h3>{{ t('配对新手机') }}</h3><span class="card-subtitle">{{ t('一次性配对码') }}</span></div></div>
          <p>{{ t('打开手机访问地址，然后输入下方配对码。仅向你自己的设备提供此码。') }}</p>
          <div v-if="pairing" class="pairing-result" role="status" aria-live="polite">
            <span>{{ t('手机配对码') }}</span><code class="pairing-code">{{ pairing.code }}</code>
            <span class="pairing-expiry">{{ t('有效期至') }} <time :datetime="pairing.expiresAt">{{ formatTime(pairing.expiresAt) }}</time></span>
          </div>
          <div v-else class="pairing-placeholder" :class="{ expired: expiredAt }" role="status"><span aria-hidden="true">{{ expiredAt ? '◷' : '···' }}</span><p>{{ expiredAt ? t('配对码已过期，请重新生成。') : t('准备好手机后，再生成配对码。') }}</p></div>
          <button type="button" class="generate-code" :disabled="!canPair || loading || !!busy" @click="generatePairing">{{ busy === 'pair' ? t('正在生成…') : pairing || expiredAt ? t('生成新配对码') : t('生成配对码') }}</button>
          <p class="fineprint">{{ t('配对码仅在此页面暂存，到期或离开页面后隐藏。') }}</p>
        </article>
      </div>

      <section class="devices-panel" :aria-label="t('已配对设备')">
        <div class="devices-heading"><div><h3>{{ t('已配对设备') }} <span class="device-count">{{ devices.length }}</span></h3><p>{{ t('撤销后，该设备需要重新配对才能继续访问。') }}</p></div></div>
        <div v-if="!devices.length" class="empty"><span class="empty-icon" aria-hidden="true">▯</span><h4>{{ t('还没有配对设备') }}</h4><p>{{ t('手机完成配对后，刷新即可在这里看到它。') }}</p></div>
        <ul v-else class="device-list">
          <li v-for="device in devices" :key="device.id" class="device-card">
            <div class="device-row"><div class="device-info"><strong>{{ device.name || t('未命名设备') }}</strong><dl><div><dt>{{ t('配对时间') }}</dt><dd><time :datetime="validDate(device.createdAt) ? device.createdAt : undefined">{{ formatTime(device.createdAt) }}</time></dd></div><div><dt>{{ t('最近访问') }}</dt><dd><time :datetime="validDate(device.lastSeenAt) ? device.lastSeenAt : undefined">{{ formatTime(device.lastSeenAt) }}</time></dd></div></dl></div><button v-if="revokingId !== device.id" type="button" class="quiet danger revoke-device" :disabled="loading || !!busy || !fresh" :aria-label="t('撤销设备 {name}', { name: device.name || t('未命名设备') })" @click="revokingId = device.id">{{ t('撤销访问') }}</button></div>
            <div v-if="revokingId === device.id" class="revoke-confirm"><p>{{ t('确定撤销 {name} 的访问权限？该设备将需要重新配对。', { name: device.name || t('未命名设备') }) }}</p><div><button type="button" class="danger confirm-revoke" :disabled="loading || !!busy || !fresh" @click="revokeDevice(device)">{{ busy === 'revoke' ? t('正在撤销…') : t('确认撤销') }}</button><button type="button" class="quiet cancel-revoke" :disabled="!!busy" @click="revokingId = null">{{ t('取消') }}</button></div></div>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { useI18n } from '../i18n/index.js'

const { t, dateLocale } = useI18n()
const status = ref({ configured: false, running: false, origin: '', devices: [] })
const loading = ref(false), ready = ref(false), fresh = ref(false), busy = ref('')
const error = ref(''), notice = ref(''), pairing = ref(null), expiredAt = ref(''), revokingId = ref(null)
const controller = new AbortController()
let disposed = false, expiryTimer
const approvalUrl = ref('')
const devices = computed(() => status.value.devices)
const origin = computed(() => safeOrigin(status.value.origin))
const needsSetup = computed(() => !status.value.configured || !status.value.running || (status.value.network && !status.value.network.connected))
const canPair = computed(() => fresh.value && !needsSetup.value && !!origin.value)

function safeOrigin(raw) {
  if (typeof raw !== 'string' || raw !== raw.trim() || !/^https:\/\//i.test(raw) || /[\s\\]/.test(raw)) return null
  try {
    const url = new URL(raw)
    return url.protocol === 'https:' && !!url.hostname && !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash ? url.origin : null
  } catch { return null }
}
function safeApprovalUrl(raw) {
  if (typeof raw !== 'string' || raw !== raw.trim() || !/^https:\/\//i.test(raw) || /[\s\\]/.test(raw)) return null
  try {
    const url = new URL(raw)
    return url.protocol === 'https:' && url.hostname === 'login.tailscale.com' && !url.username && !url.password && !url.port ? url.href : null
  } catch { return null }
}
function validDate(value) { return (typeof value === 'string' || typeof value === 'number') && value !== '' && Number.isFinite(new Date(value).getTime()) }
function formatTime(value) { return validDate(value) ? new Intl.DateTimeFormat(dateLocale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : t('暂无记录') }
function config(timeout = 15000) { return { timeout, signal: controller.signal } }
function clearPairing() { pairing.value = null; expiredAt.value = '' }
function updateExpiry() {
  if (pairing.value && new Date(pairing.value.expiresAt).getTime() <= Date.now()) { expiredAt.value = pairing.value.expiresAt; pairing.value = null }
}
function errorMessage(reason) {
  if (reason?.code === 'ECONNABORTED' || reason?.code === 'ETIMEDOUT') return '请求超时，请刷新连接状态后重试。'
  const message = reason?.response?.data?.message
  return typeof message === 'string' && message ? message : '暂时无法连接手机管理服务，请稍后重试。'
}
async function fetchStatus() {
  const { data } = await axios.get('/api/mobile-admin/status', config())
  if (disposed) return
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('Invalid mobile status')
  const next = { configured: data.configured === true, running: data.running === true, origin: typeof data.origin === 'string' ? data.origin : '', message: typeof data.message === 'string' ? data.message : '', devices: Array.isArray(data.devices) ? data.devices.filter(device => device && typeof device.id === 'string' && device.id) : [] }
  if (data.network && typeof data.network === 'object') next.network = { installed: data.network.installed === true, connected: data.network.connected === true, needsLogin: data.network.needsLogin === true, message: typeof data.network.message === 'string' ? data.network.message : '' }
  if (!next.configured || !next.running || (next.network && !next.network.connected) || safeOrigin(next.origin) !== pairing.value?.origin) clearPairing()
  if (next.network?.connected) approvalUrl.value = ''
  status.value = next; ready.value = true; fresh.value = true
  if (!next.devices.some(device => device.id === revokingId.value)) revokingId.value = null
}
async function load() {
  if (loading.value || busy.value || disposed) return
  loading.value = true; error.value = ''
  try { await fetchStatus() }
  catch (reason) { if (!disposed) { fresh.value = false; error.value = errorMessage(reason) } }
  finally { loading.value = false }
}
async function mutationConfig(timeout = 15000) {
  const { data } = await axios.get('/api/runtime/status', config())
  controller.signal.throwIfAborted()
  if (typeof data?.token !== 'string' || !data.token.trim()) throw new Error('Missing runtime authorization')
  return { ...config(timeout), headers: { 'X-Runtime-Token': data.token } }
}
async function setupNetwork() {
  if (!needsSetup.value) return
  await action('setup', async () => {
    clearPairing(); approvalUrl.value = ''
    const requestConfig = await mutationConfig(90000)
    const { data } = await axios.post('/api/mobile-admin/setup', {}, requestConfig)
    if (disposed) return
    if (data?.approvalUrl) {
      const verified = safeApprovalUrl(data.approvalUrl)
      if (!verified) { error.value = 'Tailscale 登录地址未通过验证，请刷新连接状态后重试。'; fresh.value = false; return }
      approvalUrl.value = verified
    }
    notice.value = typeof data?.message === 'string' && data.message ? data.message : '网络设置已提交，请确认连接状态后生成配对码。'
    await fetchStatus()
  })
}
async function action(kind, work) {
  if (disposed || loading.value || busy.value || !fresh.value) return
  busy.value = kind; error.value = ''; notice.value = ''
  try { await work() }
  catch (reason) { if (!disposed) { fresh.value = false; error.value = errorMessage(reason) } }
  finally { busy.value = '' }
}
async function generatePairing() {
  if (!canPair.value) return
  await action('pair', async () => {
    clearPairing()
    const requestConfig = await mutationConfig()
    const { data } = await axios.post('/api/mobile-admin/pair', {}, requestConfig)
    if (disposed) return
    const pairOrigin = safeOrigin(data?.origin)
    if (typeof data?.code !== 'string' || !data.code.trim() || !validDate(data.expiresAt) || !pairOrigin || pairOrigin !== origin.value) {
      error.value = '配对信息未通过验证，请刷新连接状态后重试。'; fresh.value = false; return
    }
    pairing.value = { code: data.code, expiresAt: data.expiresAt, origin: pairOrigin }
    updateExpiry()
  })
}
async function revokeDevice(device) {
  if (!device || revokingId.value !== device.id || !devices.value.some(item => item.id === device.id)) return
  await action('revoke', async () => {
    const requestConfig = await mutationConfig()
    await axios.delete(`/api/mobile-admin/devices/${encodeURIComponent(device.id)}`, requestConfig)
    if (disposed) return
    status.value = { ...status.value, devices: devices.value.filter(item => item.id !== device.id) }
    revokingId.value = null; notice.value = '设备访问权限已撤销。'
    await fetchStatus()
  })
}
onMounted(() => { load(); expiryTimer = setInterval(updateExpiry, 1000) })
onBeforeUnmount(() => { disposed = true; controller.abort(); clearInterval(expiryTimer); clearPairing(); approvalUrl.value = '' })
</script>

<style scoped>
.network-setup{padding:15px;margin:0 0 15px;border:1px solid #dce6ed;border-radius:13px;background:#f4f8fa}.network-setup p{font-size:12px;line-height:1.8;color:#6d8593;margin:0 0 12px}.network-setup .setup-network{font-size:12px;padding:10px 13px}.approval-link{display:block;margin-top:13px;color:#286e68;font-size:13px;line-height:1.7;overflow-wrap:anywhere}.network-setup>.fineprint{margin:9px 0 0;font-size:11px}
.mobile-connections{max-width:1440px;margin:auto;color:#20364a}.mobile-connections button{font:inherit;cursor:pointer;border:1px solid #286e68;border-radius:12px;padding:11px 16px;font-weight:700;color:#fff;background:#286e68;transition:background .18s}.mobile-connections button:hover:not(:disabled){background:#19564f}.mobile-connections button:disabled{opacity:.48;cursor:not-allowed}.mobile-connections button:focus-visible,.mobile-connections a:focus-visible{outline:3px solid #75b4de;outline-offset:3px}.mobile-connections button.quiet{border-color:#dce5ea;background:#fff;color:#435f70}.mobile-connections button.quiet:hover:not(:disabled){background:#edf5f4}.mobile-connections button.danger{color:#a54642;background:#fff;border-color:#e8d7d5}.mobile-connections button.danger:hover:not(:disabled){background:#fff1ef}.mobile-intro{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:24px}.eyebrow{font-size:10px;font-weight:800;letter-spacing:.16em;color:#728b99}.mobile-intro h2{font-size:clamp(26px,3vw,36px);line-height:1.25;letter-spacing:-.04em;margin:9px 0 12px}.mobile-intro p{margin:0;color:#687f90;line-height:1.6;font-size:14px}.mobile-intro>button{flex-shrink:0}.notice{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:14px 18px;background:#edf5f4;border:1px solid #d1e3df;border-radius:14px;font-size:14px;line-height:1.65;margin:0 0 18px;overflow-wrap:anywhere}.notice.error{background:#fff4ed;border-color:#f1dacb;color:#935a32}.notice button{flex-shrink:0;font-size:12px;padding:8px 12px}.mobile-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;margin-bottom:24px}.connection-card{border:1px solid #e0e8ee;border-radius:23px;background:linear-gradient(120deg,#fff,#f8fbfa);padding:25px;display:flex;flex-direction:column;min-width:0}.card-heading{display:flex;gap:13px;align-items:center}.card-heading h3{font-size:18px;margin:0 0 6px}.phone-icon,.pair-icon{width:46px;height:46px;flex-shrink:0;border-radius:14px;display:grid;place-items:center;font-size:32px;color:#286e68;background:#e0efea}.pair-icon{font-size:25px;background:#edf1f7;color:#57748d}.connection-status,.card-subtitle{font-size:12px;color:#82909e}.connection-status:before{content:'●';font-size:9px;margin-right:6px}.connection-status.connected{color:#28755a}.connection-card>p{font-size:13px;line-height:1.8;color:#687f90;overflow-wrap:anywhere;margin:18px 0}.origin-box{display:grid;gap:12px;padding:20px;background:#f0f6f3;border:1px solid #dceae3;border-radius:15px;margin:3px 0 0}.origin-box>span:first-child{font-size:11px;color:#788e83}.mobile-origin{color:#286e68;font-weight:700;font-size:16px;text-decoration-thickness:1px;text-underline-offset:4px;overflow-wrap:anywhere}.unavailable-origin{font-size:15px;line-height:1.7;color:#7d9187;overflow-wrap:anywhere}.connection-card>.origin-warning{font-size:12px;color:#a36d3d;margin:12px 0 0}.connection-card>.connection-note{font-size:12px;margin-top:auto;padding-top:25px;margin-bottom:0;color:#8b9c9b}.pairing-result,.pairing-placeholder{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;min-height:126px;padding:17px;box-sizing:border-box;background:#f1f7f5;border:1px solid #dbeae3;border-radius:15px;margin:2px 0 17px;text-align:center}.pairing-result>span:first-child{font-size:11px;color:#718d80}.pairing-code{font-size:clamp(26px,3vw,34px);font-weight:800;letter-spacing:.12em;color:#235d50;font-variant-numeric:tabular-nums;overflow-wrap:anywhere;max-width:100%}.pairing-expiry{font-size:11px;color:#718d80;line-height:1.7}.pairing-placeholder{background:#f7f9fa;border-color:#e5ebee;gap:4px}.pairing-placeholder>span{font-size:31px;color:#90a6ac;line-height:1.1;letter-spacing:.2em}.pairing-placeholder>p{font-size:12px;line-height:1.7;color:#8396a0;margin:8px 0 0}.pairing-placeholder.expired{background:#fff8eb;border-color:#efdfbf}.pairing-placeholder.expired p{color:#a17d44}.generate-code{width:100%}.connection-card>.fineprint{font-size:11px;text-align:center;margin:11px 0 0;color:#8b9c9b}.devices-panel{background:#ffffffc9;border:1px solid #e0e8ed;border-radius:24px;padding:26px}.devices-heading h3{font-size:21px;margin:0 0 9px;display:flex;align-items:center;gap:11px}.device-count{font-size:11px;font-weight:700;background:#e8f1ed;color:#5e8777;padding:4px 8px;border-radius:7px;font-variant-numeric:tabular-nums}.devices-heading p{font-size:13px;line-height:1.7;margin:0;color:#7c909e}.empty{padding:40px 18px;text-align:center;color:#7c909e;font-size:13px;line-height:1.7}.empty-icon{font-size:39px;color:#85a89c}.empty h4{font-size:17px;color:#486576;margin:12px 0 8px}.empty p{margin:0}.device-list{list-style:none;margin:23px 0 0;padding:0;display:grid;gap:12px}.device-card{padding:19px;border:1px solid #e3eaee;border-radius:16px;background:#fff;min-width:0}.device-row{display:flex;gap:17px;align-items:center;justify-content:space-between}.device-info{min-width:0}.device-info>strong{font-size:15px;color:#365565;overflow-wrap:anywhere}.device-info dl{display:flex;flex-wrap:wrap;gap:7px 25px;margin:12px 0 0;font-size:11px;line-height:1.7;color:#80929d}.device-info dl>div{display:flex;flex-wrap:wrap;gap:5px 9px}.device-info dd{margin:0;color:#617b8a}.revoke-device{flex-shrink:0;font-size:12px!important}.revoke-confirm{margin-top:17px;padding:15px;border-radius:12px;background:#fff7f4;border:1px solid #f0ddd4}.revoke-confirm p{margin:0 0 12px;color:#9f6252;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.revoke-confirm>div{display:flex;gap:8px;flex-wrap:wrap}.revoke-confirm button{font-size:12px;padding:9px 13px}@media(max-width:850px){.connection-card{padding:21px}.mobile-intro{align-items:flex-start}.devices-panel{padding:21px}}@media(max-width:620px){.mobile-intro{flex-direction:column}.mobile-intro h2{font-size:27px}.mobile-grid{grid-template-columns:1fr;gap:14px}.connection-card{border-radius:19px;padding:19px}.notice{align-items:flex-start;flex-direction:column}.devices-panel{padding:18px;border-radius:19px}.device-row{align-items:flex-start;flex-direction:column;gap:13px}.device-info dl{flex-direction:column;gap:4px}.devices-heading h3{font-size:19px}.device-card{padding:16px}}
</style>

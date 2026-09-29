<template>
  <ElConfigProvider :locale="elementLocale">
  <InboxWorkspace v-if="runtime.mode === 'active'">
    <template #standby>
      <div class="power-controls">
        <StandbySwitch :mode="runtime.mode" :busy="requesting" @toggle="toggle" />
        <DesktopExitButton :disabled="requesting" />
      </div>
      <span v-if="error" class="runtime-error" role="alert">{{ t(error) }}</span>
    </template>
  </InboxWorkspace>
  <main v-else class="standby-screen">
    <header><span>SMART INBOX</span></header>
    <section class="standby-card" aria-live="polite">
      <div class="sleep-icon">☾</div>
      <p class="power-eyebrow">{{ runtime.mode === 'standby' ? 'STANDBY MODE' : 'SMART INBOX' }}</p>
      <h1>{{ t(title) }}</h1>
      <p>{{ t(error || runtime.message) }}</p>
      <template v-if="runtime.mode === 'standby'">
        <div class="standby-benefits"><span>{{ t("AI 模型已卸载") }}</span><span>{{ t("收信与分析已暂停") }}</span><span>{{ t("本项目后台已停止") }}</span></div>
        <p class="standby-note">{{ t("邮件、任务、日历和追剧清单已保存在本机。关闭待机模式后自动恢复，并补同步最近 5 天的邮件。恢复服务需要一点时间。") }}</p>
        <button class="resume-button" @click="toggle" :disabled="requesting">{{ t("恢复运行") }}</button>
      </template>
      <button v-if="runtime.mode === 'error' || error" class="resume-button" @click="recover" :disabled="requesting">{{ t("恢复运行 / 重试") }}</button>
      <div v-if="runtime.mode === 'standby'" class="standby-exit"><DesktopExitButton :disabled="requesting" /></div>
    </section>
  </main>
  </ElConfigProvider>
</template>

<script setup>
import { useI18n } from './i18n/index.js'
const { t, locale } = useI18n()
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElConfigProvider } from 'element-plus'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import InboxWorkspace from './InboxWorkspace.vue'
import StandbySwitch from './components/StandbySwitch.vue'
import DesktopExitButton from './components/DesktopExitButton.vue'
const elementLocale = computed(() => locale.value === 'en-US' ? en : zhCn)

const runtime = ref({ mode: 'loading', message: '正在确认运行状态…' })
const requesting = ref(false)
const error = ref('')
let timer, disposed = false
const title = computed(() => ({ standby: '安心游戏，稍后再继续。', entering: '正在进入待机', resuming: '正在唤醒 Smart Inbox', error: '需要恢复运行', loading: '正在连接' })[runtime.value.mode])
async function status() {
  const response = await fetch('/api/runtime/status', { cache: 'no-store' })
  if (!response.ok) throw new Error('无法连接运行控制，请通过 start_all.bat 启动项目。')
  runtime.value = await response.json()
  error.value = ''
}
async function poll() {
  try { await status() } catch (exception) { error.value = exception.message }
  if (!disposed) timer = setTimeout(poll, ['entering', 'resuming'].includes(runtime.value.mode) ? 1500 : document.hidden ? 60000 : 15000)
}
async function change(action) {
  if (requesting.value) return
  requesting.value = true
  error.value = ''
  try {
    await status()
    const response = await fetch(`/api/runtime/${action}`, { method: 'POST', headers: { 'X-Runtime-Token': runtime.value.token } })
    const data = await response.json()
    if (!response.ok && response.status !== 409) throw new Error(data.message || '切换失败，请重试。')
    runtime.value = data
    clearTimeout(timer); timer = setTimeout(poll, 1000)
  } catch (exception) { error.value = exception.message }
  finally { requesting.value = false }
}
function toggle() { return change(runtime.value.mode === 'active' ? 'standby' : 'resume') }
function recover() { return change('resume') }
onMounted(poll)
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
</script>

<style scoped>
.power-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 20px; }
.standby-exit { margin-top: 24px; }
.standby-exit :deep(.desktop-exit) { color: #e2b9c0; background: #ffffff08; border-color: #e2b9c03d; }
.runtime-error { display: block; padding: 8px 0; color: #a13838; font-size: 12px; }
.standby-screen { min-height: 100vh; padding: 30px 5vw; color: #e6edfa; background: radial-gradient(ellipse at 25% 0%, #263c60, transparent 65%), #111b2c; }
.standby-screen header { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.standby-screen header > span { color: #aebed6; font-size: 12px; font-weight: 800; letter-spacing: .18em; }
.standby-card { max-width: 680px; margin: 12vh auto 0; padding: 40px; text-align: center; }
.sleep-icon { width: 92px; height: 92px; display: grid; place-items: center; margin: auto auto 28px; border: 1px solid #7189b344; border-radius: 28px; font-size: 56px; color: #c5dafd; background: #6e99db15; }
.power-eyebrow { font-size: 11px; letter-spacing: .22em; font-weight: 800; color: #809abd; }
h1 { margin: 12px 0 18px; font-size: clamp(28px, 4vw, 42px); letter-spacing: -.04em; }
.standby-card > p { color: #a8bad4; line-height: 1.9; }
.standby-benefits { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; margin: 30px 0; }
.standby-benefits span { padding: 8px 12px; border-radius: 12px; font-size: 12px; background: #ffffff0a; color: #bad2ed; }
.standby-note { font-size: 13px; }
.resume-button { border: 1px solid #6e97cc66; border-radius: 14px; margin-top: 24px; padding: 13px 28px; background: #d9e8fe; color: #243d61; font-weight: 800; }
</style>

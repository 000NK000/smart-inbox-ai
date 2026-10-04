<template>
  <div v-if="started" v-show="authenticated" :inert="!authenticated || undefined"><slot :mobile="mobile" :authenticated="authenticated" /></div>
  <main v-if="!authenticated" class="pairing-screen">
    <div class="pairing-language"><button type="button" :aria-pressed="locale === 'zh-CN'" @click="setLocale('zh-CN')">中文</button><button type="button" :aria-pressed="locale === 'en-US'" @click="setLocale('en-US')">English</button></div>
    <section class="pairing-card">
      <img src="/icons/icon.svg" width="72" height="72" alt="" /><span class="pairing-eyebrow">SMART INBOX · MOBILE</span>
      <h1>{{ ready ? t('把你的电脑放进口袋。') : t('正在连接你的电脑') }}</h1>
      <template v-if="ready && mobile">
        <p>{{ t('在电脑的“手机连接”页面生成配对码，然后在这里连接。') }}</p>
        <form @submit.prevent="pair"><label>{{ t('此设备名称') }}<input v-model.trim="deviceName" name="deviceName" maxlength="80" autocomplete="off" :placeholder="t('例如：我的 iPhone')" required :disabled="pairing" /></label><label>{{ t('电脑上的配对码') }}<input v-model.trim="pairCode" name="pairCode" class="pair-code-input" maxlength="32" autocomplete="one-time-code" autocapitalize="characters" spellcheck="false" required :disabled="pairing" /></label><button type="submit" :disabled="pairing || !pairCode.trim() || !deviceName.trim()">{{ pairing ? t('正在配对…') : t('连接这台设备') }}</button></form>
        <p class="pairing-note">{{ t('电脑需要保持开机并运行 Smart Inbox。配对不会把邮件或持仓存入手机离线缓存。') }}</p>
        <p class="pairing-note">{{ t('iPhone 与电脑请登录同一 Tailscale 账号并保持连接。') }}</p>
        <p v-if="started" class="pairing-note">{{ t('之前的页面和未保存输入仍保留在此窗口，重新连接后继续。') }}</p>
      </template>
      <p v-else>{{ checking ? t('正在确认连接状态…') : t('电脑暂时不可用。请确认电脑已开机、Smart Inbox 正在运行，并且此设备已连接 Tailscale。') }}</p>
      <p v-if="error" class="pairing-error" role="alert">{{ t(error) }}</p>
      <button v-if="!ready && !checking" type="button" class="pairing-retry" @click="initialize">{{ t('重新连接') }}</button>
    </section>
  </main>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { useI18n } from '../i18n/index.js'
import { installMobileAuthGuard, readJson, setMobileContentLock, MOBILE_AUTH_REQUIRED, MOBILE_RESUME } from '../utils/mobileAccess.js'
const { t, locale, setLocale } = useI18n()
const mobile = ref(false), ready = ref(false), authenticated = ref(false), started = ref(false), checking = ref(false), pairing = ref(false)
const error = ref(''), pairCode = ref(''), deviceName = ref('iPhone')
let removeGuard = () => {}, disposed = false
function expired() {
  if (!mobile.value) return
  authenticated.value = false
  setMobileContentLock('authentication', true)
  error.value = '此设备的连接已失效，请在电脑生成新配对码。'
}
function applyContext(context) {
  mobile.value = context.mobile === true
  authenticated.value = !mobile.value || context.authenticated === true
  ready.value = true
  if (authenticated.value) started.value = true
  document.documentElement.classList.toggle('mobile-client', mobile.value)
  setMobileContentLock('authentication', mobile.value && !authenticated.value)
}
async function initialize() {
  if (checking.value) return
  checking.value = true; error.value = ''
  try {
    let context
    try { context = await readJson('/api/mobile/context') }
    catch (exception) { if (exception.status === 404 && ['localhost', '127.0.0.1', '[::1]', '::1'].includes(window.location.hostname)) context = { mobile: false }; else throw exception }
    if (!['localhost', '127.0.0.1', '[::1]', '::1'].includes(window.location.hostname) && context.mobile !== true) throw new Error('手机连接尚未就绪，请检查电脑的手机连接设置。')
    if (!disposed) applyContext(context)
  } catch (exception) { error.value = exception.message || '暂时无法连接电脑，请稍后重试。' }
  finally { checking.value = false }
}
async function pair() {
  if (pairing.value || !mobile.value || !pairCode.value.trim() || !deviceName.value.trim()) return
  pairing.value = true; error.value = ''
  try {
    await readJson('/api/mobile/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: pairCode.value.trim(), deviceName: deviceName.value.trim() }) })
    const context = await readJson('/api/mobile/context')
    if (context.mobile !== true || context.authenticated !== true) throw new Error('尚未完成设备验证，请重新输入配对码。')
    if (!disposed) { pairCode.value = ''; applyContext(context); window.dispatchEvent(new Event(MOBILE_RESUME)) }
  } catch (exception) { error.value = exception.message || '配对失败，请检查配对码后重试。' }
  finally { pairing.value = false }
}
onMounted(() => {
  removeGuard = installMobileAuthGuard(axios, { isMobile: () => mobile.value, isLocked: () => !authenticated.value || document.documentElement.classList.contains('mobile-content-locked'), onExpired: expired })
  window.addEventListener(MOBILE_AUTH_REQUIRED, expired)
  initialize()
})
onBeforeUnmount(() => { disposed = true; removeGuard(); window.removeEventListener(MOBILE_AUTH_REQUIRED, expired); document.documentElement.classList.remove('mobile-client'); setMobileContentLock('authentication', false) })
</script>

<style scoped>
.pairing-screen{min-height:100dvh;background:radial-gradient(ellipse at 0 0,#d2e5f3,transparent 70%),#eff4f5;padding:calc(26px + env(safe-area-inset-top)) 20px calc(30px + env(safe-area-inset-bottom));color:#234457}.pairing-language{display:flex;justify-content:flex-end;gap:4px;max-width:530px;margin:auto}.pairing-language button{border:0;background:transparent;color:#7a919e;padding:9px 12px;border-radius:10px;font:inherit;font-size:12px}.pairing-language button[aria-pressed=true]{background:#fff;color:#245d73}.pairing-card{max-width:460px;margin:6vh auto 0;padding:32px;border:1px solid #fff;background:#ffffffde;border-radius:30px;box-shadow:0 24px 70px #37546913}.pairing-card>img{display:block;margin-bottom:24px;border-radius:20px}.pairing-eyebrow{font-size:10px;letter-spacing:.17em;color:#87a0ad;font-weight:800}.pairing-card h1{font-size:29px;line-height:1.25;margin:12px 0 16px;letter-spacing:-.04em}.pairing-card>p{font-size:14px;line-height:1.8;color:#708793}.pairing-card form{display:grid;gap:18px;margin-top:26px}.pairing-card label{display:grid;gap:9px;font-size:12px;color:#5d7888;font-weight:700}.pairing-card input{min-width:0;width:100%;padding:14px;border:1px solid #d7e2e9;border-radius:12px;font:inherit;font-size:16px;background:#fff;color:#234457;box-sizing:border-box}.pairing-card input:focus{outline:2px solid #89b7c7;outline-offset:2px}.pair-code-input{letter-spacing:.18em;font-weight:750}.pairing-card form>button,.pairing-retry{min-height:48px;padding:13px 20px;border:0;border-radius:12px;color:#fff;background:#2d6a7d;font:inherit;font-weight:750;cursor:pointer}.pairing-card button:disabled{opacity:.55;cursor:wait}.pairing-card .pairing-note{font-size:11px;color:#8c9fa8;margin-top:20px}.pairing-card .pairing-error{border-radius:11px;background:#fff0e7;padding:13px;color:#a26543;font-size:13px;overflow-wrap:anywhere}@media(max-width:620px){.pairing-card{padding:26px 23px;margin-top:4vh}.pairing-card h1{font-size:27px}}
</style>

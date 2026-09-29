import axios from 'axios'
import { createApp, h } from 'vue'
import 'element-plus/dist/index.css'
import '../style.css'
import { createDemoAdapter } from './fixtures.js'
import { setLocale } from '../i18n/index.js'

// Install a fail-closed transport before importing the actual application.
// No demo requests ever fall back to the collector, runtime controller or LLM.
axios.defaults.adapter = createDemoAdapter()
window.fetch = async input => {
  const url = typeof input === 'string' ? input : input.url
  if (url === '/api/runtime/status') return new Response(JSON.stringify({ mode: 'active', message: 'Read-only demo' }), { headers: { 'Content-Type': 'application/json' } })
  throw new Error('Network requests are disabled in the sample workspace.')
}
if (navigator.geolocation) Object.defineProperty(navigator.geolocation, 'getCurrentPosition', {
  value: (_success, failure) => failure?.({ code: 1, message: 'Location is disabled in demo mode.' }), configurable: true,
})
setLocale('en-US')
const { default: App } = await import('../App.vue')
createApp({ render: () => h('div', [
  h('aside', { style: 'padding:10px 24px;background:#15243b;color:#d9e8ff;text-align:center;font:600 12px/1.5 system-ui;letter-spacing:.04em' }, 'SAMPLE WORKSPACE  ·  Fictional data only  ·  Read-only UI preview  ·  No connected accounts or live AI'),
  h(App),
]) }).mount('#app')

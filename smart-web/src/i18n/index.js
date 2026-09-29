import { computed, readonly, ref } from 'vue'
import core from './core.en.js'
import productivity from './productivity.en.js'
import discovery from './discovery.en.js'
import tools from './tools.en.js'

export const LANGUAGE_STORAGE_KEY = 'smart-inbox.ui-language'
const supported = value => value === 'en-US' ? 'en-US' : 'zh-CN'
function savedLocale() {
  try { return supported(globalThis.localStorage?.getItem(LANGUAGE_STORAGE_KEY)) }
  catch { return 'zh-CN' }
}
const currentLocale = ref(savedLocale())
export const locale = readonly(currentLocale)
export const dateLocale = computed(() => currentLocale.value)
const english = { ...core, ...productivity, ...discovery, ...tools }

// Only explicitly marked interface messages pass through this dictionary.
// Mail bodies, news, AI reports and personal notes never enter a translator.
export function t(message, params = {}) {
  const source = String(message ?? '')
  const template = currentLocale.value === 'en-US' ? (english[source] ?? source) : source
  return template.replace(/\{(\w+)\}/g, (match, key) => Object.hasOwn(params, key) ? String(params[key]) : match)
}
function updateDocument() {
  if (typeof document !== 'undefined') document.documentElement.lang = currentLocale.value
  try { globalThis.window?.smartInboxDesktop?.setLocale?.(currentLocale.value) } catch { /* Older desktop shells remain compatible. */ }
}
export function setLocale(value) {
  if (!['zh-CN', 'en-US'].includes(value)) return
  currentLocale.value = value
  try { globalThis.localStorage?.setItem(LANGUAGE_STORAGE_KEY, value) } catch { /* Private browsing can disable storage. */ }
  updateDocument()
}
updateDocument()
if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key === LANGUAGE_STORAGE_KEY) { currentLocale.value = supported(event.newValue); updateDocument() }
})
export function useI18n() { return { t, locale, dateLocale, setLocale } }

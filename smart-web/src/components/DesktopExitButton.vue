<template>
  <button v-if="desktop" class="desktop-exit" type="button" :disabled="disabled || pending" @click="exit" :title="t('保存数据、停止本项目后台并关闭 App')" :aria-label="t('退出软件')">
    <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M12 3v9M6.3 5.8a9 9 0 1 0 11.4 0" /></svg>
    <span>{{ pending ? t('正在退出…') : t('退出软件') }}</span>
  </button>
</template>
<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { onBeforeUnmount, ref } from 'vue'
defineProps({ disabled: Boolean })
const desktop = typeof window.smartInboxDesktop?.exit === 'function'
const pending = ref(false)
let timer
function exit() {
  if (pending.value) return
  pending.value = window.smartInboxDesktop.exit()
  // The native window shows shutdown progress; unlock if it reports a failure.
  clearTimeout(timer)
  timer = setTimeout(() => { pending.value = false }, 4000)
}
onBeforeUnmount(() => clearTimeout(timer))
</script>
<style scoped>
.desktop-exit { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 37px; padding: 8px 13px; border: 1px solid #d9a7ad66; border-radius: 13px; color: #9b505c; background: #fff6f5dd; font-size: 12px; font-weight: 750; cursor: pointer; white-space: nowrap; }
.desktop-exit:hover { background: #ffe8e6; border-color: #c8848e; }
.desktop-exit:focus-visible { outline: 3px solid #d9a7ad; outline-offset: 3px; }
.desktop-exit:disabled { opacity: .6; cursor: wait; }
</style>

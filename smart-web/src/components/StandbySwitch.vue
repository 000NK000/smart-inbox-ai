<template>
  <button class="standby-switch" type="button" role="switch" :aria-label="t('待机模式')" :aria-checked="mode === 'standby' || mode === 'entering'" :disabled="busy || ['loading','entering','resuming'].includes(mode)" @click="$emit('toggle')" :title="t('待机时停止本项目后台、卸载 AI 模型；关闭后恢复运行')">
    <span class="moon">☾</span><span>{{ t('待机模式') }}</span><span class="toggle-track" :class="{ on: mode === 'standby' || mode === 'entering' }"><i /></span>
    <small v-if="mode === 'entering'">{{ t('正在待机…') }}</small><small v-else-if="mode === 'resuming'">{{ t('正在恢复…') }}</small>
  </button>
</template>
<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
defineProps({ mode: String, busy: Boolean })
defineEmits(['toggle'])
</script>
<style scoped>
.standby-switch { display: inline-flex; align-items: center; gap: 9px; min-height: 37px; padding: 7px 13px; border: 1px solid #94a8c22e; border-radius: 13px; background: #f4f8fee8; color: #4c6486; font-size: 12px; font-weight: 750; box-shadow: 0 4px 18px #17345306; }
.moon { font-size: 19px; line-height: 1; }
.toggle-track { width: 31px; height: 19px; border-radius: 20px; padding: 3px; background: #c3cedd; }
.toggle-track i { display: block; width: 13px; height: 13px; border-radius: 50%; background: white; }
.toggle-track.on { background: #6189c9; }
.toggle-track.on i { transform: translateX(12px); }
button:disabled { opacity: .65; cursor: wait; }
button:focus-visible { outline: 3px solid #91b9ee; outline-offset: 3px; }
small { font-size: 10px; }
</style>

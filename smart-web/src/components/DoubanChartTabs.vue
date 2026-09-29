<template>
  <footer :class="['douban-footer', { dark }]">
    <div class="chart-note">
      <span>{{ t(current?.status?.status || '正在读取豆瓣榜单…') }}<template v-if="current?.items?.length && current.items.length < 10"> · {{ t('本期共 {count} 部', { count: current.items.length }) }}</template></span>
      <a v-if="current?.url" :href="current.url" target="_blank" rel="noopener noreferrer">{{ t('豆瓣原榜 ↗') }}</a>
    </div>
    <nav :aria-label="label" class="douban-tabs">
      <button v-for="chart in collections" :key="chart.id" type="button" :class="{ active: chart.id === current?.id }" :aria-pressed="chart.id === current?.id" @click="$emit('select', chart.id)">{{ t(chart.name) }}</button>
    </nav>
  </footer>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
defineProps({ collections: { type: Array, default: () => [] }, current: Object, dark: Boolean, label: String })
defineEmits(['select'])
</script>

<style scoped>
.douban-footer{margin-top:18px;padding-top:16px;border-top:1px solid #7c889d26}.chart-note{display:flex;justify-content:space-between;gap:12px;color:#8994a5;font-size:10px;margin-bottom:12px;line-height:1.5}.chart-note a{color:#b94e64;text-decoration:none;white-space:nowrap}.douban-tabs{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:4px;padding:5px;background:#edf1f6;border-radius:14px}.douban-tabs button{border:0;background:transparent;border-radius:10px;padding:11px 2px;color:#778398;font:inherit;font-size:11px;font-weight:750;cursor:pointer;white-space:nowrap}.douban-tabs button.active{color:#c23853;background:#fff;box-shadow:0 3px 12px #23365414}.douban-tabs button:focus-visible{outline:2px solid #397ef0;outline-offset:2px}.dark .douban-tabs{background:#ffffff0b}.dark .douban-tabs button{color:#a8b7cd}.dark .douban-tabs button.active{color:#fff;background:#3d6db7}.dark .chart-note a{color:#9dbcf2}@media(max-width:450px){.douban-tabs{gap:1px;padding:3px}.douban-tabs button{font-size:10px;padding:10px 1px}}
.douban-tabs button{white-space:normal;line-height:1.35;min-height:42px;overflow-wrap:anywhere}
</style>

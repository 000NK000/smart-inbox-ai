<template>
  <span class="watch-poster" :class="{ unavailable: failed || !src }">
    <span class="poster-placeholder" aria-hidden="true">{{ item.title?.slice(0, 1) || '▣' }}</span>
    <img v-if="src && !failed" :key="src" :src="src" :alt="t('{title} 封面', { title: item.title })" :loading="loading" referrerpolicy="no-referrer" @error="failed = true" />
    <button v-if="failed && retryable" type="button" class="poster-retry" @click.stop="retry">{{ t('重新加载封面') }}</button>
  </span>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed, ref, watch } from 'vue'
const props = defineProps({ item: { type: Object, required: true }, loading: { type: String, default: 'lazy' }, retryable: Boolean })
const failed = ref(false), attempt = ref(0)
const baseSrc = computed(() => {
  if (!props.item.poster) return ''
  return props.item.source === '豆瓣' ? `/api/dashboard/watch/douban/poster/${encodeURIComponent(props.item.id)}` : props.item.poster
})
const src = computed(() => baseSrc.value && (attempt.value ? `${baseSrc.value}${baseSrc.value.includes('?') ? '&' : '?'}retry=${attempt.value}` : baseSrc.value))
watch([baseSrc, () => props.item], () => { failed.value = false; attempt.value = 0 })
function retry() { attempt.value++; failed.value = false }
</script>

<style scoped>
.watch-poster{position:relative;display:grid;width:100%;height:100%;overflow:hidden;place-items:center;background:#dfe7f2;color:#6f7b90;border-radius:inherit}.poster-placeholder{font-weight:850;font-size:1.15em}.watch-poster img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.poster-retry{position:absolute;bottom:15px;left:50%;transform:translateX(-50%);white-space:nowrap;border:0;border-radius:9px;padding:8px 10px;color:#4a5d7c;background:#ffffffde;cursor:pointer;font-size:11px}
</style>

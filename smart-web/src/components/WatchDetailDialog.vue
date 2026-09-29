<template>
  <ElDialog :model-value="!!item" :title="item?.title || t('作品介绍')" width="min(760px, calc(100vw - 32px))" align-center append-to-body destroy-on-close class="watch-detail-dialog" @update:model-value="value => { if (!value) $emit('close') }">
    <template v-if="item">
      <div class="detail-top">
        <div class="detail-poster"><WatchPoster :item="item" loading="eager" retryable /></div>
        <div class="detail-info">
          <span class="detail-source">{{ t(item.source) }} · {{ t(item.sourceDetail || '作品介绍') }}</span>
          <h2>{{ item.title }}</h2>
          <div class="detail-badges"><span>{{ t(kindLabel) }}</span><span v-if="item.year">{{ item.year }}</span><strong v-if="item.rating">{{ item.source === '烂番茄' ? `🍅 ${item.rating}%` : `★ ${item.rating}` }}</strong><span v-else>{{ t('暂无评分') }}</span></div>
          <p v-if="item.metadata" class="metadata">{{ item.metadata }}</p>
          <button type="button" class="add-detail" :disabled="adding || saved" @click="$emit('add')">{{ saved ? t('✓ 已在我的清单') : adding ? t('加入中…') : t('＋ 加入想看') }}</button>
        </div>
      </div>
      <section class="detail-synopsis" :aria-label="t('作品简介')">
        <h3>{{ t('作品简介') }}</h3>
        <p v-if="item.description">{{ item.description }}</p>
        <p v-else class="no-description">{{ t('当前榜单暂未提供简介，可以前往官方页面了解更多。') }}</p>
      </section>
      <footer class="detail-footer"><small>{{ t('资料来自 {source}', { source: t(item.source) }) }}</small><a v-if="officialUrl" class="official-link" :href="officialUrl" target="_blank" rel="noopener noreferrer">{{ t('打开 {source} 官方页面', { source: t(item.source) }) }} ↗</a></footer>
    </template>
  </ElDialog>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed } from 'vue'
import { ElDialog } from 'element-plus'
import WatchPoster from './WatchPoster.vue'
const props = defineProps({ item: Object, kind: String, saved: Boolean, adding: Boolean })
defineEmits(['close', 'add'])
const kindLabel = computed(() => ({ MOVIE: '电影', TV: '电视剧', VARIETY: '综艺' }[props.kind] || '影视作品'))
const officialUrl = computed(() => {
  try {
    const url = new URL(props.item?.url || '')
    const domain = { '豆瓣': 'douban.com', IMDb: 'imdb.com', '烂番茄': 'rottentomatoes.com' }[props.item?.source]
    return domain && ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password && (url.hostname === domain || url.hostname.endsWith(`.${domain}`)) ? url.href : ''
  } catch { return '' }
})
</script>

<style scoped>
.detail-top{display:grid;grid-template-columns:180px minmax(0,1fr);gap:28px;align-items:start}.detail-poster{width:180px;height:266px;border-radius:15px;box-shadow:0 12px 28px #26365222;overflow:hidden;font-size:42px}.detail-info{padding-top:8px;min-width:0}.detail-source{font-size:11px;letter-spacing:.04em;color:#9a7b85}.detail-info h2{color:#1c293e;font-size:27px;line-height:1.35;margin:10px 0 16px;overflow-wrap:anywhere}.detail-badges{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.detail-badges span{background:#edf1f7;color:#738198;padding:5px 9px;border-radius:7px;font-size:11px}.detail-badges strong{color:#cf8241;font-size:16px;margin-left:4px}.metadata{font-size:13px;line-height:1.8;color:#748097;overflow-wrap:anywhere}.add-detail{margin-top:12px;border:0;border-radius:11px;background:#fff0f3;color:#c43f59;font-weight:750;padding:11px 16px;cursor:pointer}.add-detail:disabled{opacity:.55;cursor:default}.detail-synopsis{margin-top:28px;padding-top:22px;border-top:1px solid #e6ebf3}.detail-synopsis h3{color:#26374e;font-size:15px;margin:0 0 12px}.detail-synopsis p{font-size:14px;line-height:1.9;color:#526077;margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.detail-synopsis .no-description{color:#8c97a7}.detail-footer{display:flex;justify-content:space-between;align-items:center;gap:16px;border-top:1px solid #e6ebf3;margin-top:24px;padding-top:20px}.detail-footer small{color:#94a0b2;font-size:11px}.official-link{padding:12px 17px;border-radius:12px;background:linear-gradient(135deg,#ec6576,#c73653);color:white;font-weight:750;font-size:12px;text-decoration:none}.official-link:focus-visible,.add-detail:focus-visible{outline:2px solid #397ef0;outline-offset:3px}@media(max-width:560px){.detail-top{grid-template-columns:110px minmax(0,1fr);gap:16px}.detail-poster{width:110px;height:164px}.detail-info h2{font-size:21px}.detail-source{font-size:9px}.metadata{font-size:11px}.detail-footer{flex-wrap:wrap}.official-link{margin-left:auto}}
</style>

<style>
.el-dialog.watch-detail-dialog{border-radius:25px;padding:26px;background:#fbfcff;max-height:calc(100dvh - 32px);overflow:auto}.watch-detail-dialog .el-dialog__header{padding:0 32px 20px 0}.watch-detail-dialog .el-dialog__title{font-size:14px;color:#6b7a91;font-weight:650}.watch-detail-dialog .el-dialog__headerbtn{top:14px;right:14px}
</style>

<template>
  <section :class="['ranking-section', { dark }]">
    <header>
      <div class="title-icon">{{ icon }}</div>
      <div><span>{{ eyebrow }}</span><h3>{{ title }}</h3></div>
      <b>{{ items.length }}</b>
    </header>
    <ol v-if="items.length">
      <li v-for="(item,index) in items.slice(0,10)" :key="`${item.source}-${item.id}-${index}`">
        <button class="open-detail" type="button" :aria-label="t('查看 {title} 的介绍', { title: item.title })" @click="$emit('details', item)">
          <strong class="rank">{{ String(item.sourceRank || index + 1).padStart(2,'0') }}</strong>
          <div class="poster-shell">
            <WatchPoster :item="item" />
          </div>
          <div class="item-copy">
            <h4>{{ item.title }}</h4>
            <p><span>{{ t(item.source) }}</span><span v-if="item.year">{{ item.year }}</span><span v-if="item.rating">{{ item.source === '烂番茄' ? `🍅 ${item.rating}%` : `★ ${item.rating}` }}</span></p>
          </div>
          <span class="open-arrow" aria-hidden="true">›</span>
        </button>
        <button class="add-watch" type="button" :disabled="adding || isSaved(item)" :aria-label="t(isSaved(item) ? '已收藏 {title}' : '加入想看 {title}', { title: item.title })" @click="$emit('add', item)">{{ isSaved(item) ? t('✓ 已收藏') : t('＋ 想看') }}</button>
      </li>
    </ol>
    <div v-else class="empty-state">{{ t(emptyLabel) }}</div>
    <slot name="footer"></slot>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import WatchPoster from './WatchPoster.vue'
const props = defineProps({
  savedTitles: { type: Array, default: () => [] },
  adding: { type: Boolean, default: false },
  icon: { type: String, default: '◉' },
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  items: { type: Array, default: () => [] },
  emptyLabel: { type: String, default: '暂无榜单' },
  dark: { type: Boolean, default: false }
})
defineEmits(['add', 'details'])
function isSaved(item) { return props.savedTitles.includes(item.title.normalize('NFKC').trim().toLowerCase().replace(/\s+/g, ' ')) }
</script>

<style scoped>
li{display:flex;align-items:center;gap:8px}li>.open-detail{flex:1;min-width:0}.add-watch{flex-shrink:0;padding:7px 9px;border:0;border-radius:9px;color:#c54860;background:#ffedf0;font-size:11px;font-weight:750;cursor:pointer}.add-watch:disabled{opacity:.5;cursor:default}.dark .add-watch{background:#334565;color:#c4d8ff}.add-watch:focus-visible{outline:2px solid #397ef0;outline-offset:3px}

.ranking-section { overflow: hidden; border: 1px solid rgba(255,255,255,.9); border-radius: 30px; padding: 25px; color: #192438; background: rgba(255,255,255,.86); box-shadow: 0 20px 50px rgba(51,66,91,.11); }
.ranking-section.dark { color: #f7f9fd; background: linear-gradient(155deg,#25334b,#111827); }
header { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 13px; margin-bottom: 13px; }
.title-icon { display: grid; width: 46px; height: 46px; place-items: center; border-radius: 14px; color: #fff; font-size: 20px; background: linear-gradient(145deg,#ff736c,#dd394f); box-shadow: 0 10px 24px rgba(214,55,75,.22); }
.dark .title-icon { background: linear-gradient(145deg,#669eff,#3267c9); box-shadow: 0 10px 24px rgba(47,99,190,.25); }
header span { color: #8b95a6; font-size: 9px; font-weight: 900; letter-spacing: .14em; }
header h3 { margin: 3px 0 0; font-size: 26px; letter-spacing: -.04em; }
header b { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 50%; color: #c03c4c; font-size: 12px; background: #ffe9e7; }
.dark header b { color: #dce8ff; background: rgba(255,255,255,.1); }
ol { padding: 0; margin: 0; list-style: none; }
li + li { border-top: 1px solid rgba(124,136,157,.15); }
.open-detail { border:0; background:transparent; font:inherit; text-align:left; cursor:pointer; width:100%; display: grid; grid-template-columns: 28px 50px minmax(0,1fr) auto; align-items: center; gap: 11px; min-height: 61px; padding: 8px 2px; color: inherit; text-decoration: none; }
.open-detail:hover h4 { color: #3478e5; }
.rank { color: #9aa4b4; font-size: 11px; font-variant-numeric: tabular-nums; }
.poster-shell { position: relative; display: grid; width: 50px; height: 72px; overflow: hidden; place-items: center; border-radius: 9px; color: #6f7b90; font-weight: 900; background: #dfe7f2; }
.open-detail:focus-visible { outline:2px solid #397ef0; outline-offset:2px; border-radius:10px; }
.item-copy { min-width: 0; }
h4 { overflow: hidden; margin: 0; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; transition: color .18s; }
p { display: flex; gap: 8px; margin: 5px 0 0; color: #8b95a7; font-size: 9px; }
p span:first-child { color: #d14a52; font-weight: 850; }
.dark p span:first-child { color: #83aef7; }
.open-arrow { color: #9ba6b8; }
.empty-state { display: grid; min-height: 420px; place-items: center; color: #8c97a9; }
</style>

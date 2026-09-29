<template>
  <section class="hotspot-center">
    <header class="center-intro">
      <div>
        <span>TRENDS & NEWS</span>
        <h2>{{ t('热点中心') }}</h2>
        <p>{{ t('平台趋势与主流媒体分开整理，点击卡片查看完整榜单。') }}</p>
      </div>
      <button type="button" :disabled="loading" @click="$emit('refresh')">{{ loading ? t('更新中…') : t('全部刷新') }}</button>
    </header>

    <div class="channel-grid">
      <button class="channel-card social-card" type="button" @click="$emit('navigate', 'social-trends')">
        <div class="channel-heading"><div><span>SOCIAL · REALTIME</span><h3>{{ t('平台热榜') }}</h3></div><b>{{ t('微博 + B站') }}</b></div>
        <ol>
          <li v-for="item in socialPreview" :key="`${item.platform}-${item.url}`"><em>{{ item.title }}</em><small>{{ t(item.platform) }}</small></li>
        </ol>
        <span class="open-link">{{ t('查看各平台前 10 条 ›') }}</span>
      </button>

      <button class="channel-card media-card" type="button" @click="$emit('navigate', 'world-news')">
        <div class="channel-heading"><div><span>U.S. MEDIA · TRENDING</span><h3>{{ t('美国媒体热榜') }}</h3></div><b>TOP 10</b></div>
        <ol>
          <li v-for="item in worldNews.slice(0, 5)" :key="item.url"><em>{{ item.title }}</em><small>{{ item.source }}</small></li>
        </ol>
        <span class="open-link">{{ t('查看综合热榜 ›') }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed } from 'vue'

const props = defineProps({
  platforms: { type: Array, default: () => [] },
  worldNews: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
defineEmits(['navigate', 'refresh'])

const socialPreview = computed(() => props.platforms
  .filter(platform => platform.enabled && platform.available)
  .flatMap(platform => (platform.items || []).slice(0, 3).map(item => ({ ...item, platform: platform.name })))
  .slice(0, 5))
</script>

<style scoped>
.hotspot-center { width: min(1220px, 100%); margin: 0 auto; }
.center-intro { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.center-intro span,.channel-heading span { color: #8490a4; font-size: 10px; font-weight: 900; letter-spacing: .16em; }
.center-intro h2 { margin: 5px 0 4px; font-size: clamp(28px,4vw,42px); letter-spacing: -.05em; }
.center-intro p { margin: 0; color: #748096; font-size: 13px; }
.center-intro button { border: 0; border-radius: 13px; padding: 11px 17px; color: #fff; font-weight: 850; background: linear-gradient(145deg,#438aff,#2163db); }
.channel-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 22px; }
.channel-card { display: flex; min-height: 580px; flex-direction: column; padding: clamp(24px,3.4vw,38px); border: 1px solid rgba(255,255,255,.9); border-radius: 32px; text-align: left; box-shadow: 0 24px 55px rgba(50,65,90,.13); transition: transform .2s, box-shadow .2s; }
.channel-card:hover { transform: translateY(-4px); box-shadow: 0 30px 66px rgba(50,65,90,.18); }
.social-card { color: #182235; background: linear-gradient(150deg,rgba(255,255,255,.98),rgba(255,245,238,.92)); }
.media-card { color: #f8fafc; background: linear-gradient(150deg,#25334b,#111827); }
.channel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.channel-heading h3 { margin: 5px 0 0; font-size: 32px; letter-spacing: -.045em; }
.channel-heading b { padding: 8px 11px; border-radius: 999px; color: #a9441d; font-size: 10px; letter-spacing: .08em; background: #ffe2d3; }
.media-card .channel-heading b { color: #d9e7ff; background: rgba(255,255,255,.1); }
.media-card .channel-heading span,.media-card small { color: #9fb0ca; }
ol { display: grid; gap: 0; padding: 0; margin: 25px 0; list-style: none; }
li { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 18px; padding: 17px 0; border-bottom: 1px solid rgba(120,130,150,.18); }
em { overflow: hidden; font-size: 14px; font-style: normal; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
small { color: #947466; white-space: nowrap; }
.open-link { margin-top: auto; font-size: 12px; font-weight: 850; opacity: .72; }
@media (max-width: 820px) { .channel-grid { grid-template-columns: 1fr; } .channel-card { min-height: 470px; } }
</style>

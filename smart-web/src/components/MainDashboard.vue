<template>
  <main class="dashboard-shell">
    <section class="ipad-surface">
      <header class="dashboard-header">
        <div class="quote-block">
          <p class="eyebrow">SMART INBOX</p>
          <h1>“{{ philosophyQuote.text }}”</h1>
          <p class="quote-author" :title="philosophyQuote.work">— {{ philosophyQuote.author }}</p>
        </div>
        <div class="clock-block">
          <strong>{{ clock }}</strong>
          <span>{{ dateLabel }}</span>
        </div>
      </header>

      <section class="today-strip"><button type="button" @click="$emit('navigate','today')"><b>{{ t("今日安排 ›") }}</b><span>{{ t("今日到期") }} {{ todaySummary?.dueToday || 0 }} {{ t("· 已逾期") }} {{ todaySummary?.overdue || 0 }} {{ t("· 即将到期") }} {{ todaySummary?.upcoming || 0 }}</span></button><button type="button" @click="$emit('navigate','operations')">{{ t("运行状态与备份 ⚙") }}</button></section>
      <section class="hub-entry-grid">
      <button class="calendar-entry" type="button" @click="$emit('navigate', 'calendar')">
        <span class="calendar-entry-icon" aria-hidden="true">▦</span>
        <span class="calendar-entry-copy"><small>YOUR WEEK, AT A GLANCE</small><strong>{{ t("日历与课程中心") }}</strong><span>{{ t("课程 · 考试 · 面试 · 任务截止日期") }}</span></span>
        <span class="calendar-entry-hint">{{ t("周 / 月视图") }} <b>{{ t("打开日历 ↗") }}</b></span>
      </button>
      <button class="career-entry" type="button" @click="$emit('navigate', 'applications')">
        <span class="career-entry-icon" aria-hidden="true">↗</span>
        <span class="calendar-entry-copy"><small>APPLICATION PIPELINE</small><strong>{{ t("求职与申请中心") }}</strong><span>{{ t("准备 · 已投 · 笔试 · 面试 · 结果") }}</span></span>
        <span class="calendar-entry-hint">{{ t("招聘邮件建议") }} <b>{{ t("管理申请 ↗") }}</b></span>
      </button>
      <button class="calendar-entry focus-entry" type="button" @click="$emit('navigate', 'focus')">
        <span class="calendar-entry-icon focus-entry-icon" aria-hidden="true">◷</span>
        <span class="calendar-entry-copy"><small>FOCUS & WEEKLY REVIEW</small><strong>{{ t("专注与每周复盘") }}</strong><span>{{ t("招工 · 法语 · 课程计时 · 娱乐倒计时") }}</span></span>
        <span class="calendar-entry-hint">{{ t("每日时间记录") }} <b>{{ t("打开计时 ↗") }}</b></span>
      </button>
      <button class="calendar-entry practice-entry" type="button" @click="$emit('navigate', 'practice')">
        <span class="calendar-entry-icon practice-entry-icon" aria-hidden="true">{ }</span>
        <span class="calendar-entry-copy"><small>JAVA · INTERVIEW PREP</small><strong>{{ t("刷题进度与熟练度") }}</strong><span>{{ t("自主记录 · 两档水平 · 随机抽弱题") }}</span></span>
        <span class="calendar-entry-hint">{{ t("建议题单可选") }} <b>{{ t("打开我的题库 ↗") }}</b></span>
      </button>
      </section>
      <section class="widget-grid">
        <button class="widget weather-widget" type="button" @click="$emit('navigate', 'weather')">
          <div class="widget-topline">
            <span class="location-dot">●</span>
            <span>{{ weather.location || 'Ithaca' }}</span>
            <span class="open-hint">{{ t("查看全天 ›") }}</span>
          </div>
          <div v-if="weather.loading" class="widget-loading">{{ t("正在读取天气…") }}</div>
          <template v-else>
            <div class="weather-main">
              <span class="weather-glyph">{{ weather.icon || '☁️' }}</span>
              <div>
                <strong>{{ weather.temperature ?? '--' }}°</strong>
                <p>{{ weather.label || t('天气数据暂不可用') }} {{ t("· 体感") }} {{ weather.feelsLike ?? '--' }}°</p>
              </div>
            </div>
            <div v-if="weather.hourly?.length" class="mini-weather-chart">
              <svg viewBox="0 0 420 64" preserveAspectRatio="none" aria-hidden="true">
                <polyline :points="miniChartPoints" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <div><span>{{ t("现在") }} {{ weather.hourly[0].temperature }}°</span><b>{{ t("最高降雨") }} {{ maxRain }}%</b><span>24:00 {{ weather.hourly.at(-1).temperature }}°</span></div>
            </div>
          </template>
        </button>

        <button class="widget shortcut-widget mail-widget" type="button" @click="$emit('navigate', 'mail')">
          <div class="app-icon mail-icon">✉</div>
          <div>
            <span class="widget-kicker">{{ t("邮件中心") }}</span>
            <strong>{{ mailCount }}</strong>
            <p>{{ t("封近期邮件已完成 AI 整理") }}</p>
          </div>
          <span class="corner-arrow">↗</span>
        </button>

        <button class="widget shortcut-widget task-widget" type="button" @click="$emit('navigate', 'tasks')">
          <div class="app-icon task-icon">✓</div>
          <div>
            <span class="widget-kicker">{{ t("先记再做") }}</span>
            <strong>{{ taskCount }}</strong>
            <p>{{ t("件未完成事项，本机数据库同步") }}</p>
          </div>
          <span class="corner-arrow">↗</span>
        </button>

        <button class="widget center-widget hotspot-widget" type="button" @click="$emit('navigate', 'hotspot-center')">
          <div class="center-heading">
            <div>
              <span class="widget-kicker">TRENDS & NEWS</span>
              <h2>{{ t("热点中心") }}</h2>
            </div>
            <span class="center-icon hot">⌁</span>
          </div>
          <div class="hotspot-preview">
            <section>
              <b>{{ t("平台热榜") }}</b><small>{{ t("微博 · B站") }}</small>
              <p>{{ trendPreview[0]?.title || t('打开查看实时平台趋势') }}</p>
            </section>
            <section>
              <b>{{ t("美国媒体") }}</b><small>CNN · NBC · ABC</small>
              <p>{{ worldNews[0]?.title || t('打开查看主流媒体热榜') }}</p>
            </section>
          </div>
          <span class="read-more">{{ t("进入热点中心 ›") }}</span>
        </button>

        <button class="widget center-widget watch-widget" type="button" @click="$emit('navigate', 'watch-center')">
          <div class="cinema-glow"></div>
          <div class="center-heading">
            <div>
              <span class="widget-kicker">DOUBAN × IMDb × RT</span>
              <h2>{{ t("追剧中心") }}</h2>
            </div>
            <span class="center-icon cinema">▶</span>
          </div>
          <div class="watch-preview">
            <section><small>{{ t("电影榜单 · 最近预览") }}</small><b>{{ watchPreview.movie || t('打开查看最新榜单') }}</b></section>
            <section><small>{{ t("剧集榜单 · 最近预览") }}</small><b>{{ watchPreview.tv || t('打开查看最新榜单') }}</b></section>
          </div>
          <span class="read-more">{{ t("查看豆瓣、IMDb 与烂番茄独立榜单 ›") }}</span>
        </button>
      </section>

      <footer class="dashboard-footer">
        <div class="dashboard-power"><slot name="standby" /></div>
        <button class="credentials-shortcut" type="button" :aria-label="t('密钥管理')" @click="$emit('navigate', 'credentials')">
          <span class="credentials-icon" aria-hidden="true">⌘</span><small>{{ t("密钥") }}</small>
        </button>
        <div class="dashboard-language" role="group" :aria-label="t('界面语言')">
          <span aria-hidden="true">◎</span>
          <button type="button" lang="zh-CN" :aria-pressed="locale === 'zh-CN'" :title="t('切换为中文')" @click="setLocale('zh-CN')">中文</button>
          <button type="button" lang="en" :aria-pressed="locale === 'en-US'" :title="t('切换为英文')" @click="setLocale('en-US')">English</button>
        </div>
      </footer>
    </section>
  </main>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t, locale, setLocale } = useI18n()
import { computed } from 'vue'
import { usePhilosophyQuote } from '../utils/philosophyQuotes.js'

const props = defineProps({
  weather: { type: Object, required: true },
  trendPlatforms: { type: Array, default: () => [] },
  worldNews: { type: Array, default: () => [] },
  watchData: { type: Object, default: () => ({ movies: [], tvShows: [] }) },
  mailCount: { type: Number, default: 0 },
  taskCount: { type: Number, default: 0 },
  clock: { type: String, default: '' },
  todaySummary: { type: Object, default: () => ({}) },
  dateLabel: { type: String, default: '' }
})

defineEmits(['navigate'])

const philosophyQuote = usePhilosophyQuote(locale)

const miniChartPoints = computed(() => {
  const hours = props.weather.hourly || []
  if (!hours.length) return ''
  const values = hours.map(item => Number(item.temperature))
  const min = Math.min(...values), max = Math.max(...values), range = Math.max(1, max - min)
  return values.map((value, index) => `${8 + index * 404 / Math.max(1, values.length - 1)},${54 - (value - min) / range * 42}`).join(' ')
})
const maxRain = computed(() => Math.max(0, ...(props.weather.hourly || []).map(item => Number(item.rain))))
const trendPreview = computed(() => props.trendPlatforms
  .filter(platform => platform.enabled && platform.available)
  .flatMap(platform => (platform.items || []).slice(0, 2).map(item => ({
    ...item,
    platformId: platform.id,
    platformName: platform.name
  })))
  .slice(0, 4))
const watchPreview = computed(() => ({
  movie: props.watchData?.movies?.[0]?.items?.[0]?.title || '',
  tv: props.watchData?.tvShows?.[0]?.items?.[0]?.title || ''
}))
</script>

<style scoped>
.hub-entry-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));grid-auto-rows:1fr;gap:16px;margin:0 0 22px}.calendar-entry,.career-entry{display:flex;align-items:center;gap:18px;width:100%;padding:20px 24px;border:1px solid #fff;border-radius:24px;text-align:left;color:#244268;background:linear-gradient(115deg,#fdfefe,#e3edff);box-shadow:0 10px 28px #38577f0b;cursor:pointer}.career-entry{background:linear-gradient(115deg,#fffdf9,#fff0de)}.calendar-entry:hover{background:linear-gradient(115deg,#fff,#d8e7ff)}.career-entry:hover{background:linear-gradient(115deg,#fff,#ffe5c7)}.calendar-entry:focus-visible,.career-entry:focus-visible{outline:3px solid #689df0;outline-offset:3px}.calendar-entry-icon,.career-entry-icon{display:grid;place-items:center;flex-shrink:0;width:57px;height:57px;border-radius:17px;background:linear-gradient(145deg,#669cf9,#456cd5);color:white;font-size:34px}.career-entry-icon{background:linear-gradient(145deg,#ff9b57,#e6603d)}.calendar-entry-copy{display:grid;gap:5px;min-width:0}.calendar-entry-copy small{font-size:9px;letter-spacing:1.5px;color:#7990b0;font-weight:800}.calendar-entry-copy strong{font-size:20px}.calendar-entry-copy>span{font-size:12px;color:#7183a1}.calendar-entry-hint{display:grid;gap:7px;margin-left:auto;text-align:right;font-size:11px;color:#7183a1;white-space:nowrap}.calendar-entry-hint b{font-size:13px;color:#4167a8}@media(max-width:900px){.hub-entry-grid{grid-template-columns:1fr}}@media(max-width:620px){.calendar-entry,.career-entry{gap:12px;padding:17px 14px}.calendar-entry-copy strong{font-size:17px}.calendar-entry-hint{font-size:0}.calendar-entry-hint b{font-size:11px}.calendar-entry-icon,.career-entry-icon{width:44px;height:48px;font-size:29px}}
.practice-entry{background:linear-gradient(115deg,#fbfdff,#e7f6f2)}.practice-entry:hover{background:linear-gradient(115deg,#fff,#d9f2e9)}.practice-entry-icon{background:linear-gradient(145deg,#46b8a3,#2e7fb0);font-size:23px}
.today-strip{display:flex;justify-content:space-between;gap:16px;margin:0 0 24px;flex-wrap:wrap}.today-strip button{display:flex;gap:18px;align-items:center;flex-wrap:wrap;border:1px solid #ffffffb0;background:#ffffff9a;color:#315781;padding:14px 20px;border-radius:16px}.today-strip span{font-size:13px}
.dashboard-footer { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: center; gap: 20px; margin-top: 22px; }
.dashboard-power { grid-column: 1; justify-self: start; }
.dashboard-language { grid-column: 3; justify-self: end; display: flex; align-items: center; gap: 4px; padding: 5px; border: 1px solid #dce5f1; border-radius: 16px; background: #f8fbffdf; box-shadow: 0 6px 18px #38577f08; }
.dashboard-language > span { padding: 0 7px; color: #748ba9; font-size: 21px; }
.dashboard-language button { border: 0; border-radius: 11px; background: transparent; color: #617592; font-size: 13px; font-weight: 750; padding: 10px 13px; cursor: pointer; }
.dashboard-language button[aria-pressed="true"] { background: #fff; color: #2466da; box-shadow: 0 3px 12px #3765a41a; }
.dashboard-language button:focus-visible { outline: 3px solid #639fff; outline-offset: 2px; }
:global(html[lang="en-US"] .calendar-entry-hint) { white-space: normal; max-width: 150px; }
:global(html[lang="en-US"] .calendar-entry-copy strong) { font-size: 18px; }
.dashboard-shell {
  min-height: 100vh;
  padding: 22px;
  background:
    radial-gradient(circle at 10% 0%, rgba(117, 184, 255, .48), transparent 34rem),
    radial-gradient(circle at 100% 78%, rgba(255, 186, 150, .36), transparent 32rem),
    linear-gradient(145deg, #dce9f7 0%, #edf1f6 52%, #dce4ee 100%);
}

.ipad-surface {
  width: min(1540px, 100%);
  min-height: calc(100vh - 44px);
  margin: 0 auto;
  padding: 34px clamp(22px, 4vw, 58px) 24px;
  border: 1px solid rgba(255, 255, 255, .8);
  border-radius: 38px;
  background: rgba(248, 250, 253, .72);
  box-shadow: 0 30px 90px rgba(54, 70, 96, .22), inset 0 1px 0 #fff;
  backdrop-filter: blur(28px) saturate(1.35);
}

.dashboard-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 26px;
}

.eyebrow, .widget-kicker { margin: 0; color: #6d7789; font-size: 12px; font-weight: 800; letter-spacing: .13em; }
.quote-block { min-width: 0; max-width: min(1050px, calc(100% - 150px)); }
.dashboard-header h1 { margin: 4px 0 0; color: #172033; font-size: clamp(25px, 2.55vw, 40px); line-height: 1.18; letter-spacing: -.04em; }
.quote-author { margin: 8px 2px 0; color: #68758a; font-size: 13px; font-weight: 750; letter-spacing: .04em; }
.clock-block { display: grid; justify-items: end; color: #2b3547; }
.clock-block strong { font-size: 30px; letter-spacing: -.04em; }
.clock-block span { color: #778195; font-size: 13px; }

.widget-grid {
  display: grid;
  min-height: calc(100vh - 350px);
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-template-rows: 215px minmax(340px, 1fr);
  gap: 20px;
}

.widget {
  position: relative;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, .84);
  border-radius: 30px;
  padding: 24px;
  color: #182235;
  text-align: left;
  background: rgba(255, 255, 255, .86);
  box-shadow: 0 15px 40px rgba(57, 70, 94, .11), inset 0 1px 0 rgba(255,255,255,.9);
  transition: transform .22s ease, box-shadow .22s ease;
}
.widget:hover { transform: translateY(-4px) scale(1.005); box-shadow: 0 22px 48px rgba(57, 70, 94, .17); }
.widget:focus-visible { outline: 4px solid rgba(42, 117, 255, .28); outline-offset: 3px; }

.weather-widget {
  grid-column: span 2;
  color: #fff;
  background:
    radial-gradient(circle at 84% 18%, rgba(255,255,255,.4), transparent 8rem),
    linear-gradient(135deg, #428bff 0%, #6eaaff 52%, #8fc4ff 100%);
}
.weather-widget::after { content: ''; position: absolute; width: 220px; height: 220px; right: -45px; bottom: -110px; border-radius: 50%; background: rgba(255,255,255,.16); }
.widget-topline { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 700; }
.location-dot { color: #fff; font-size: 8px; }
.open-hint { margin-left: auto; opacity: .84; }
.weather-main { display: flex; align-items: center; gap: 18px; margin-top: 18px; }
.weather-glyph { font-size: 52px; filter: drop-shadow(0 8px 12px rgba(0,0,0,.1)); }
.weather-main strong { font-size: 52px; line-height: .9; letter-spacing: -.08em; }
.weather-main p { margin: 9px 0 0; opacity: .88; font-size: 13px; }
.mini-weather-chart { margin-top: 11px; }
.mini-weather-chart svg { display: block; width: 100%; height: 43px; overflow: visible; }
.mini-weather-chart div { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 10px; opacity: .86; }
.mini-weather-chart b { padding: 4px 8px; border-radius: 999px; background: rgba(255,255,255,.16); }

.shortcut-widget { display: flex; flex-direction: column; justify-content: space-between; }
.shortcut-widget strong { display: block; margin-top: 8px; font-size: 44px; letter-spacing: -.06em; }
.shortcut-widget p { margin: 1px 0 0; color: #758095; font-size: 13px; line-height: 1.45; }
.app-icon { display: grid; width: 54px; height: 54px; place-items: center; border-radius: 16px; color: #fff; font-size: 27px; font-weight: 800; box-shadow: 0 11px 25px rgba(41, 92, 190, .25); }
.mail-icon { background: linear-gradient(145deg, #367dff, #0f5adf); }
.task-icon { background: linear-gradient(145deg, #34c77a, #119c56); }
.corner-arrow { position: absolute; top: 24px; right: 24px; color: #8993a5; font-size: 22px; }

.center-widget { grid-column: span 2; display: flex; flex-direction: column; }
.hotspot-widget { background: linear-gradient(150deg,rgba(255,255,255,.97),rgba(255,245,238,.91)); }
.watch-widget { color: #f8fafc; background: linear-gradient(145deg,#273753 0%,#111827 72%); }
.watch-widget .widget-kicker { color: #9fb0ca; }
.cinema-glow { position: absolute; width: 260px; height: 260px; right: -60px; top: -85px; border-radius: 50%; background: radial-gradient(circle,rgba(93,143,255,.45),transparent 66%); }
.center-heading { position: relative; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.center-heading h2 { margin: 4px 0 0; font-size: 30px; letter-spacing: -.04em; }
.center-icon { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 14px; color: #fff; font-size: 20px; font-weight: 900; box-shadow: 0 9px 22px rgba(38,65,110,.18); }
.center-icon.hot { background: linear-gradient(145deg,#ff806d,#e44956); }
.center-icon.cinema { background: linear-gradient(145deg,#649bff,#3564c2); }
.hotspot-preview,.watch-preview { position: relative; display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; margin: 20px 0 16px; }
.hotspot-preview section,.watch-preview section { overflow: hidden; padding: 16px; border-radius: 18px; background: rgba(255,255,255,.68); box-shadow: inset 0 0 0 1px rgba(122,137,159,.1); }
.hotspot-preview b,.watch-preview b { display: block; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.hotspot-preview small,.watch-preview small { display: block; margin-top: 3px; color: #9a7667; font-size: 9px; font-weight: 800; }
.hotspot-preview p { overflow: hidden; margin: 12px 0 0; color: #59667a; font-size: 11px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.watch-preview section { color: #eef4ff; background: rgba(255,255,255,.075); box-shadow: inset 0 0 0 1px rgba(255,255,255,.08); }
.watch-preview small { margin: 0 0 8px; color: #9fb3d3; }
.watch-preview b { font-size: 14px; }
.read-more { margin-top: auto; font-size: 12px; font-weight: 800; opacity: .72; }
.widget-loading { display: grid; min-height: 100px; place-items: center; opacity: .72; }

.credentials-shortcut {
  grid-column: 2;
  justify-self: center;
  display: grid;
  justify-items: center;
  gap: 6px;
  min-width: 64px;
  margin: 0;
  padding: 8px;
  border: 0;
  border-radius: 16px;
  color: #606b7f;
  background: transparent;
  cursor: pointer;
  appearance: none;
}
.credentials-shortcut:focus-visible { outline: 2px solid #3478e5; outline-offset: 4px; }
.credentials-shortcut small { font-size: 11px; font-weight: 700; }
.credentials-icon { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 13px; color: #fff; font-size: 20px; font-weight: 800; background: linear-gradient(145deg,#667085,#303846); box-shadow: 0 8px 17px rgba(41,50,67,.18); transition: transform .15s ease; }
.credentials-shortcut:hover .credentials-icon { transform: translateY(-2px); }

@media (max-width: 980px) {
  .widget-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: auto; }
  .weather-widget, .center-widget { grid-column: span 2; }
  .weather-widget { min-height: 220px; }
  .shortcut-widget { min-height: 200px; }
  .center-widget { min-height: 300px; }
}

@media (max-width: 620px) {
  .dashboard-footer { grid-template-columns: 1fr; gap: 24px; }
  .dashboard-power { grid-row: 2; }
  .dashboard-language { grid-column: 1; grid-row: 3; justify-self: end; }
  .credentials-shortcut { grid-column: 1; grid-row: 1; }
  .dashboard-shell { padding: 0; }
  .ipad-surface { min-height: 100vh; padding: 24px 15px; border-radius: 0; }
  .dashboard-header { align-items: flex-start; }
  .quote-block { max-width: calc(100% - 78px); }
  .dashboard-header h1 { font-size: 27px; }
  .quote-author { margin-top: 6px; font-size: 11px; }
  .clock-block strong { font-size: 22px; }
  .clock-block span { display: none; }
  .widget-grid { grid-template-columns: 1fr; }
  .weather-widget, .center-widget { grid-column: span 1; }
  .hotspot-preview,.watch-preview { grid-template-columns: 1fr; }
  .shortcut-widget { min-height: 180px; }
}
.focus-entry { background: linear-gradient(115deg,#f9fffb,#e7f8ee); }
.focus-entry:hover { background: linear-gradient(115deg,#fff,#d9f2e5); }
.focus-entry-icon { background: linear-gradient(145deg,#45b891,#18866a); }
</style>

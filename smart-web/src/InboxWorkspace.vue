<template>
  <div v-if="mobile && page === 'home'" class="mobile-online" role="status"><span aria-hidden="true"></span>{{ t('已连接你的电脑') }}</div>
  <MainDashboard
    v-if="page === 'home'"
    ref="workspaceRoot"
    :weather="weatherView"
    :mobile="mobile"
    :trend-platforms="trendPlatforms"
    :world-news="worldNews"
    :watch-data="watchData"
    :mail-count="mailCount"
    :task-count="taskCount"
    :clock="clock"
    :date-label="dateLabel"
    :today-summary="taskSummary"
    @navigate="navigate"
  ><template #standby><slot name="standby" /></template></MainDashboard>

  <div v-else ref="workspaceRoot" class="workspace-shell">
    <header class="workspace-header">
      <button class="home-button" type="button" :aria-label="t('主菜单')" :title="t('主菜单')" @click="navigate('home')"><span class="home-button-icon" aria-hidden="true">‹</span><span class="home-button-label">{{ t("主菜单") }}</span></button>
      <div class="page-identity"><span>{{ pageEyebrow }}</span><strong>{{ pageTitle }}</strong></div>
      <div class="header-status" role="status" :title="mobile ? t('已连接你的电脑') : t('Smart Inbox 在线')"><span class="online-dot" aria-hidden="true"></span><span class="header-status-label">{{ mobile ? t('已连接你的电脑') : t('Smart Inbox 在线') }}</span></div>
    </header>

    <main class="workspace-content">
      <section v-if="page === 'mail'" class="mail-workspace">
        <div class="mail-toolbar">
          <div class="mail-filter-stack">
            <div class="inbox-tabs" role="tablist" :aria-label="t('本地邮件状态')">
              <button v-for="view in mailViews" :key="view.key" type="button" :class="{ active: mailView === view.key }" @click="selectMailView(view.key)">{{ t(view.label) }}</button>
            </div>
            <div v-if="mailView !== 'PLAN'" class="mail-tabs" role="tablist" :aria-label="t('邮箱来源')">
              <button v-for="channel in mailChannels" :key="channel.key" type="button" :class="{ active: mailChannel === channel.key }" @click="selectMailChannel(channel.key)">{{ t(channel.label) }}</button>
            </div>
          </div>
          <div v-if="mailView !== 'PLAN'" class="mail-actions">
            <el-switch v-model="blockAd" :active-text="t('屏蔽广告')" :inactive-text="t('显示广告')" />
            <button class="refresh-button" type="button" :disabled="mailLoading || outlookSyncing" @click="refreshMailbox">{{ mailLoading || outlookSyncing ? t('刷新中…') : t('刷新邮件') }}</button>
          </div>
        </div>

        <div v-if="page === 'mail' && mailView !== 'PLAN'" class="mail-search">
          <form @submit.prevent="fetchSummaries()"><input v-model="searchText" maxlength="200" :placeholder="t('搜索主题、发件人或正文')" :aria-label="t('搜索邮件')" /><button type="submit">{{ t("搜索") }}</button><button v-if="searchText" type="button" @click="searchText='';fetchSummaries()">{{ t("清除") }}</button></form>
          <select v-model="searchHours" :aria-label="t('邮件时间范围')"><option :value="120">{{ t("最近 5 天") }}</option><option :value="72">{{ t("最近 3 天") }}</option><option :value="24">{{ t("最近 24 小时") }}</option></select>
          <label><input v-model="searchStarred" type="checkbox" />{{ t("仅星标") }}</label>
          <button type="button" :disabled="reminderSettingBusy" @click="enableNotifications">{{ remindersEnabled ? t('关闭邮件提醒') : t('启用邮件提醒') }}</button>
        </div>
        <MailTaskPlan v-if="page === 'mail' && mailView === 'PLAN'" :key="mailPlanRevision" @open-mail="openMailDetail" />
        <template v-else>
        <div v-if="!mobile && page === 'mail' && (mailChannel === 'OUTLOOK' || mailChannel === 'ALL') && outlookStatus.connected === false && !outlookSyncing && !outlookStatus.sync?.syncing && (outlookStatus.sync?.failureCode || outlookStatus.desktopState) !== 'starting'" class="mail-source-notice">
          <div><strong>{{ t("Outlook 当前未连接") }}</strong><span>{{ outlookStatusMessage }} {{ t("当前列表显示的是历史缓存，不能代表 Outlook 的实时收件情况。") }}</span></div>
          <button type="button" @click="navigate('credentials')">{{ t("前往连接设置") }}</button>
        </div>

        <div v-if="page === 'mail'" class="mail-sync-status" role="status">
          <span v-if="mailView === 'INBOX'">{{ t("最近 120 小时 · 包含原邮箱已读邮件 · 仅在此处标为已读后移出") }}</span>
          <span v-if="mobile">{{ t('邮件采集在电脑运行；刷新只重新读取电脑已同步的内容。') }}</span>
          <span v-else>{{ outlookSyncing || outlookStatus.sync?.syncing ? t('正在检查 Outlook 新邮件…') : outlookStatus.connected ? t('Outlook 已连接 · 每分钟自动检查') : t('Outlook 等待连接，将自动重试') }}</span>
          <span v-if="outlookStatus.sync?.lastSuccess">{{ t("最近成功同步：") }}{{ formatTime(outlookStatus.sync.lastSuccess) }}</span>
        </div>
        <div v-if="mailLoading" class="center-state"><el-icon class="is-loading"><Loading /></el-icon><span>{{ t("AI 正在整理邮件…") }}</span></div>
        <div v-else-if="!summaries.length" class="center-state"><span class="empty-symbol">✉</span><strong>{{ t("暂时没有新内容") }}</strong><small>{{ t("点击刷新重新检查") }}</small></div>
        <div v-else class="mail-list">
          <article v-for="item in summaries" :key="item.id" class="mail-card" tabindex="0" @click="openMailDetail(item)" @keydown.enter="openMailDetail(item)">
            <div class="mail-card-head">
              <div class="sender-avatar">{{ avatarText(item.subject) }}</div>
              <div class="mail-title"><h3 :title="item.subject">{{ item.subject }}</h3><span>{{ formatSource(item.source) }}</span></div>
              <div class="mail-card-side">
                <div class="mail-tags"><span :class="['urgency', `u-${item.urgency || 1}`]">{{ t("紧急度") }} {{ item.urgency || 1 }}</span><span class="category">{{ item.category }}</span></div>
                <div v-if="page === 'mail'" class="mail-quick-actions">
                  <button type="button" :class="{ active: item.starred }" :title="item.starred ? t('取消本地星标') : t('添加本地星标')" @click.stop="toggleMailStar(item)">{{ item.starred ? '★' : '☆' }}</button>
                  <button v-if="!item.inboxRead" type="button" :title="t('在 Smart Inbox 中标为已读')" @click.stop="markMailRead(item)">{{ t("✓ 已读") }}</button>
                  <span v-else class="local-read-state">{{ t("已读") }}</span>
                  <button v-if="mailView === 'SNOOZED'" type="button" @click.stop="cancelSnooze(item)">{{ t("移回收件箱") }}</button>
                  <button v-else-if="!item.inboxRead" type="button" @click.stop="snoozeMail(item)">{{ t("稍后提醒") }}</button>
                </div>
              </div>
            </div>
            <p>{{ item.summary || t('暂无摘要。') }}</p>
            <footer><time>{{ formatTime(item.createdTime) }}</time><span>{{ item.action === 'IGNORE' ? t('已忽略') : t('点击查看完整邮件') }}</span></footer>
          </article>
        </div>
        <div v-if="!mailLoading" class="mail-list-footer"><span>{{ mailView === 'STARRED' ? t('已收藏的星标邮件') : t('最近 5 天（120 小时）的邮件') }} {{ t("· 已显示") }} {{ summaries.length }} / {{ mailTotal }}</span><button v-if="summaries.length < mailTotal" type="button" @click="loadMoreMail">{{ t("加载更多") }}</button></div>
        </template>
      </section>

      <TaskManager v-else-if="page === 'tasks'" @open-mail="openMailDetail" />
      <TodayDashboard v-else-if="page === 'today'" @open-mail="openMailDetail" @navigate="navigate" />
      <CalendarCenter v-else-if="page === 'calendar'" @open-mail="openMailDetail" @navigate="navigate" />
      <JobApplicationCenter v-else-if="page === 'applications'" @open-mail="openMailDetail" />
      <FocusCenter v-else-if="page === 'focus'" />
      <PracticeCenter v-else-if="page === 'practice'" />
      <StocksCenter v-else-if="page === 'stocks'" :mobile="mobile" />
      <MobileConnectionPanel v-else-if="!mobile && page === 'mobile-connection'" />
      <OperationsPanel v-else-if="!mobile && page === 'operations'" @restored="onRestored" />
      <WeatherPanel v-else-if="page === 'weather'" :weather="weatherView" :locations="availableLocations" :location-key="locationKey" :analysis="rainAnalysis" :analysis-loading="rainAnalysisLoading" :locating="locating" @location-change="changeLocation" @request-location="detectLocation" />
      <SocialTrendsPanel v-else-if="page === 'social-trends'" :platforms="trendPlatforms" :loading="trendsLoading" @refresh="fetchTrends(true)" />
      <UsNewsPanel v-else-if="page === 'world-news'" :combined="worldNews" :sources="usNewsSources" :loading="usNewsLoading" @refresh="fetchUsNews(true)" />
      <HotspotCenterPanel v-else-if="page === 'hotspot-center'" :platforms="trendPlatforms" :world-news="worldNews" :loading="trendsLoading || usNewsLoading" @navigate="navigate" @refresh="refreshHotspotCenter" />
      <WatchCenterPanel v-else-if="page === 'watch-center'" :movies="watchData.movies" :tv-shows="watchData.tvShows" :sources="watchData.sources" :loading="watchLoading" @refresh="fetchWatch(true)" />
      <section v-else-if="!mobile && page === 'credentials'" class="credentials-workspace"><CredentialManager /></section>
    </main>
  </div>

  <InboxChat v-if="page === 'mail' && mailView !== 'PLAN'" />

  <div v-if="mailDetailVisible" class="mail-detail-backdrop" role="presentation" @click.self="closeMailDetail">
    <section class="mail-detail-sheet" role="dialog" aria-modal="true" :aria-label="t('邮件详情')">
      <header class="mail-detail-header">
        <div>
          <span>MESSAGE DETAIL · {{ formatSource(selectedMail?.source) }}</span>
          <h2>{{ selectedMail?.originalSubject || selectedMail?.subject || t('邮件详情') }}</h2>
        </div>
        <button type="button" :aria-label="t('关闭邮件详情')" @click="closeMailDetail">×</button>
      </header>
      <div v-if="mailDetailLoading" class="mail-detail-loading"><el-icon class="is-loading"><Loading /></el-icon><span>{{ t("正在读取完整邮件…") }}</span></div>
      <template v-else-if="selectedMail">
        <div class="mail-detail-meta">
          <div class="sender-avatar large">{{ avatarText(selectedMail.sender || selectedMail.subject) }}</div>
          <div><strong>{{ selectedMail.sender || t('未知发件人') }}</strong><small>{{ formatTime(selectedMail.createdTime) }}</small></div>
          <div class="mail-tags"><span :class="['urgency', `u-${selectedMail.urgency || 1}`]">{{ t("紧急度") }} {{ selectedMail.urgency || 1 }}</span><span class="category">{{ selectedMail.category }}</span></div>
        </div>
        <div class="mail-detail-actions">
          <button type="button" :class="{ active: selectedMail.starred }" @click="toggleMailStar(selectedMail)">{{ selectedMail.starred ? t('★ 已星标') : t('☆ 添加星标') }}</button>
          <button type="button" class="read-action" :disabled="selectedMail.inboxRead" @click="markMailRead(selectedMail)">{{ selectedMail.inboxRead ? t('✓ 已从收件箱移除') : t('✓ 标为已读并移出收件箱') }}</button>
        </div>
        <div v-if="selectedMail && !selectedMail.inboxRead" class="mail-detail-actions"><button type="button" @click="snoozeMail(selectedMail)">{{ t("◷ 稍后提醒") }}</button></div>
        <section class="mail-insight-card" :aria-label="t('AI 邮件分析')">
          <div class="mail-insight-heading">
            <div><span>AI ANALYSIS</span><h3>{{ t("AI 分析") }}</h3></div>
            <button type="button" :disabled="mailInsightLoading" @click="analyzeSelectedMail">{{ mailInsightLoading ? t('正在分析…') : mailInsightResult ? t('重新分析') : t('分析这封邮件') }}</button>
          </div>
          <p v-if="mailInsightLoading" class="mail-insight-help" role="status">{{ t("正在阅读邮件正文并整理中文报告，请稍候…") }}</p>
          <p v-else-if="mailInsightError" class="mail-insight-error" role="alert">{{ t(mailInsightError) }}</p>
          <div v-if="mailInsightResult" class="mail-insight-report">
            <div class="mail-insight-section"><strong>{{ t("这封邮件是做什么的") }}</strong><p>{{ mailInsightResult.purpose }}</p></div>
            <div class="mail-insight-section"><strong>{{ t("你需要知道的重点") }}</strong><ul><li v-for="(point, index) in mailInsightResult.keyPoints" :key="index">{{ point }}</li></ul></div>
            <div class="mail-insight-section"><strong>{{ t("我需要做什么") }}</strong><span :class="['mail-action-status', mailInsightResult.actionStatus.toLowerCase()]">{{ mailActionLabel(mailInsightResult.actionStatus) }}</span><p>{{ mailInsightResult.actionExplanation }}</p></div>
            <div v-if="mailInsightResult.importantTimes.length" class="mail-insight-section"><strong>{{ t("重要时间") }}</strong><ul><li v-for="(time, index) in mailInsightResult.importantTimes" :key="index">{{ time }}</li></ul></div>
            <div v-if="mailInsightResult.notes.length" class="mail-insight-section"><strong>{{ t("需要留意") }}</strong><ul><li v-for="(note, index) in mailInsightResult.notes" :key="index">{{ note }}</li></ul></div>
            <p v-if="mailInsightResult.scopeNote" class="mail-insight-scope">{{ mailInsightResult.scopeNote }}</p>
            <small>{{ t("这份分析仅依据邮件内容生成；具体安排以原邮件为准。") }}</small>
          </div>
          <p v-else-if="!mailInsightLoading && !mailInsightError" class="mail-insight-help">{{ t("点击按钮后，AI 会用中文说明邮件的用途、重点以及是否需要你处理。") }}</p>
        </section>
        <MailBody :key="selectedMail.id" :html="selectedMail.htmlContent" :text="selectedMail.content || selectedMail.summary" />
      </template>
    </section>
  </div>
  <div v-if="focusShortcutNotice && page !== 'focus'" class="focus-shortcut-notice" :class="focusShortcutNotice.category?.toLowerCase()" role="status" aria-live="polite">{{ t(focusShortcutNotice.message, { category: t(focusShortcutNotice.category === 'EFFECTIVE' ? '有效时间' : '无效时间') }) }}</div>
</template>

<script setup>
import { useI18n } from './i18n/index.js'
const props = defineProps({ mobile: { type: Boolean, default: false } })
const { t, dateLocale } = useI18n()
import { ElSwitch, ElIcon } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import MainDashboard from './components/MainDashboard.vue'
import { createAsyncPanel } from './utils/asyncPanel.js'
const TaskManager = createAsyncPanel(() => import('./components/TaskManager.vue'))
const WeatherPanel = createAsyncPanel(() => import('./components/WeatherPanel.vue'))
const SocialTrendsPanel = createAsyncPanel(() => import('./components/SocialTrendsPanel.vue'))
const UsNewsPanel = createAsyncPanel(() => import('./components/UsNewsPanel.vue'))
const HotspotCenterPanel = createAsyncPanel(() => import('./components/HotspotCenterPanel.vue'))
const WatchCenterPanel = createAsyncPanel(() => import('./components/WatchCenterPanel.vue'))
const InboxChat = createAsyncPanel(() => import('./components/InboxChat.vue'))
const MailBody = createAsyncPanel(() => import('./components/MailBody.vue'))
const MailTaskPlan = createAsyncPanel(() => import('./components/MailTaskPlan.vue'))
const CredentialManager = createAsyncPanel(() => import('./components/CredentialManager.vue'))
const TodayDashboard = createAsyncPanel(() => import('./components/TodayDashboard.vue'))
const CalendarCenter = createAsyncPanel(() => import('./components/CalendarCenter.vue'))
const JobApplicationCenter = createAsyncPanel(() => import('./components/JobApplicationCenter.vue'))
const FocusCenter = createAsyncPanel(() => import('./components/FocusCenter.vue'))
const PracticeCenter = createAsyncPanel(() => import('./components/PracticeCenter.vue'))
const StocksCenter = createAsyncPanel(() => import('./components/StocksCenter.vue'))
const MobileConnectionPanel = createAsyncPanel(() => import('./components/MobileConnectionPanel.vue'))
const OperationsPanel = createAsyncPanel(() => import('./components/OperationsPanel.vue'))
import { useTaskStore } from './stores/taskStore'
import { ElNotification, ElMessageBox } from 'element-plus'
import { readPreview, savePreview } from './utils/previewCache'
import { createFocusController, focusControllerKey } from './stores/focusController.js'
import { installFocusShortcut } from './utils/focusShortcut.js'

const focusController = createFocusController(axios, Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Toronto')
provide(focusControllerKey, focusController)
const workspaceRoot = ref(null), focusShortcutNotice = ref(null)
let removeFocusShortcut, focusNoticeTimer
function focusShortcutEnabled() {
  const root = workspaceRoot.value?.$el || workspaceRoot.value
  return !disposed && !!root && !root.closest?.('[inert]')
}
function showFocusShortcutNotice(result) {
  clearTimeout(focusNoticeTimer)
  if (result.status === 'ignored') { focusShortcutNotice.value = null; return }
  focusShortcutNotice.value = { ...result, message: result.status === 'switched' ? '已切换为「{category}」' : result.message }
  if (result.status) focusNoticeTimer = setTimeout(() => { focusShortcutNotice.value = null }, 5000)
}

const locations = [
  { key: 'waterloo', name: 'Waterloo · 滑铁卢', latitude: 43.4643, longitude: -80.5204 },
  { key: 'ithaca', name: 'Ithaca · 伊萨卡', latitude: 42.4406, longitude: -76.4966 },
  { key: 'beijing', name: '北京', latitude: 39.9042, longitude: 116.4074 },
  { key: 'new-york', name: 'New York · 纽约', latitude: 40.7128, longitude: -74.0060 },
  { key: 'toronto', name: 'Toronto · 多伦多', latitude: 43.6532, longitude: -79.3832 }
]
const mailChannels = [
  { key: 'ALL', label: '全部邮件' },
  { key: 'GMAIL', label: 'Gmail' },
  { key: 'QQMAIL', label: 'QQ 邮箱' },
  { key: 'OUTLOOK', label: 'Outlook' }
]
const mailViews = [
  { key: 'INBOX', label: '收件箱' },
  { key: 'STARRED', label: '星标邮件' },
  { key: 'SNOOZED', label: '稍后提醒' },
  { key: 'PLAN', label: '邮件任务规划' }
]

const page = ref('home')
const mailChannel = ref('ALL')
const mailView = ref('INBOX')
const mailPlanRevision = ref(0)
const mailLoading = ref(false)
const summaries = ref([])
const mailCount = ref(0)
const mailTotal = ref(0)
const currentMailPage = ref(0)
const searchText = ref('')
const searchHours = ref(120)
const searchStarred = ref(false)
let mailVersion = ''
let reminderTimer
let reminderBusy = false
const remindersEnabled = ref(false)
const reminderSettingBusy = ref(false)
let detailRequestId = 0
let insightRequestId = 0
const notifiedInPage = new Set()
const mailDetailVisible = ref(false)
const mailDetailLoading = ref(false)
const selectedMail = ref(null)
const mailInsightLoading = ref(false)
const mailInsightResult = ref(null)
const mailInsightError = ref('')
const blockAd = ref(false)
const outlookStatus = ref({ connected: null, configured: false, desktopState: '' })
const outlookSyncing = ref(false)
const trendPlatforms = ref([])
const trendsLoading = ref(false)
const worldNews = ref([])
const usNewsSources = ref([])
const usNewsLoading = ref(false)
const watchData = ref({ movies: [], tvShows: [], sources: [], updatedAt: null })
const watchLoading = ref(false)
const { summary: taskSummary, refresh: refreshTasks } = useTaskStore({ items: false })
const taskCount = computed(() => taskSummary.value.open || 0)
const savedLocation = localStorage.getItem('smart-inbox.weather-location')
const locationKey = ref(savedLocation && savedLocation !== 'ithaca' && savedLocation !== 'detected' ? savedLocation : 'waterloo')
const detectedLocation = ref(null)
const locating = ref(false)
const locationDetected = ref(false)
const rawWeather = ref(null)
const weatherLoading = ref(false)
const rainAnalysis = ref(null)
const rainAnalysisLoading = ref(false)
const now = ref(new Date())
let clockTimer
let weatherRequestId = 0
let rainRequestId = 0
let disposed = false
let reminderSettingsReady = false
let mailPollTimer
let mailboxOpeningSync = 0
let mailRequestId = 0

const clock = computed(() => new Intl.DateTimeFormat(dateLocale.value, { hour: '2-digit', minute: '2-digit', hour12: false }).format(now.value))
const dateLabel = computed(() => new Intl.DateTimeFormat(dateLocale.value, { month: 'long', day: 'numeric', weekday: 'long' }).format(now.value))
const availableLocations = computed(() => detectedLocation.value ? [detectedLocation.value, ...locations] : locations)
const activeLocation = computed(() => availableLocations.value.find(item => item.key === locationKey.value) || locations[0])

const pageTitle = computed(() => ({ 'mobile-connection': t('手机连接'), stocks: t('股票中心'), practice: t('刷题进度与熟练度'), focus: t('专注与每周复盘'), applications: t('求职与申请中心'), calendar: t('日历与课程中心'), today: t('今日安排'), operations: t('运行与备份'), mail: t('邮件'), tasks: t('任务管理'), weather: t('今日天气'), 'hotspot-center': t('热点中心'), 'social-trends': t('平台热榜'), 'world-news': t('美国媒体热榜'), 'watch-center': t('追剧中心'), credentials: t('密钥管理') }[page.value] || 'Smart Inbox'))
const pageEyebrow = computed(() => ({ 'mobile-connection': 'PRIVATE MOBILE ACCESS', stocks: 'PORTFOLIO & RESEARCH', practice: 'JAVA · INTERVIEW PREP', focus: 'FOCUS & REVIEW', applications: 'CAREER PIPELINE', calendar: 'CALENDAR', today: 'TODAY', operations: 'SYSTEM', mail: 'INBOX', tasks: 'REMINDERS', weather: 'WEATHER', 'hotspot-center': 'TRENDS & NEWS', 'social-trends': 'SOCIAL TRENDS', 'world-news': 'U.S. MEDIA', 'watch-center': 'DOUBAN × IMDb × RT', credentials: 'LOCAL VAULT' }[page.value] || 'SMART INBOX'))
const outlookStatusMessage = computed(() => {
  const state = outlookStatus.value.sync?.failureCode || outlookStatus.value.desktopState
  if (state === 'offline') return t('经典 Outlook 处于离线模式，请恢复网络并关闭脱机工作。')
  if (state === 'timeout') return t('读取 Outlook 超时，系统会自动重试。')
  if (state === 'unavailable') return t('无法访问本机经典 Outlook，请用 start_all.bat 启动项目并确认 Outlook 账号仍已登录。')
  if (state === 'sync_failed' || state === 'desktop_sync_failed') return t('同步未完成，系统会自动重试；请确认消息队列及本地数据目录可用。')
  if (state === 'profile_required') return t('请在经典 Outlook 中选择已有的邮箱配置，并设为默认配置；完成一次选择后，系统会自动重试同步。')
  if (state === 'no_profile') return t('经典 Outlook 尚未设置可用的默认邮箱配置，请先在电脑上打开 Outlook 并完成配置。')
  if (state === 'starting') return t('经典 Outlook 正在启动，请稍候；系统会自动重试同步。')
  if (state === 'account_not_found') return t('经典 Outlook 中没有找到 mailbox@example.com。')
  if (outlookStatus.value.configured) return t('Microsoft 应用已经配置，但账号授权尚未完成。')
  return t('尚未配置可用的 Microsoft 连接。')
})

const weatherView = computed(() => {
  const data = rawWeather.value
  if (!data?.current || !data?.hourly) return { location: t(activeLocation.value.name), loading: weatherLoading.value, periods: [], hourly: [] }
  const current = data.current
  const times = data.hourly.time || []
  const currentStamp = String(current.time || times[0] || '')
  let today = currentStamp.slice(0, 10)
  let currentHour = Number(currentStamp.slice(11, 13) || new Date().getHours())
  try {
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: data.timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23' }).formatToParts(new Date()).filter(part => part.type !== 'literal').map(part => [part.type, part.value]))
    today = `${parts.year}-${parts.month}-${parts.day}`
    currentHour = Number(parts.hour)
  } catch { /* Open-Meteo current time remains the fallback. */ }
  const hourly = times.map((time, index) => ({ time: String(time), index })).filter(item => {
    const date = item.time.slice(0, 10)
    const hour = Number(item.time.slice(11, 13))
    return (date === today && hour >= currentHour) || (date !== today && hour === 0)
  }).map((item, visibleIndex) => ({
    time: item.time.slice(11, 16) === '00:00' && item.time.slice(0, 10) !== today ? '24:00' : item.time.slice(11, 16),
    rawTime: item.time,
    isNow: visibleIndex === 0,
    icon: weatherMeta(data.hourly.weather_code?.[item.index]).icon,
    label: weatherMeta(data.hourly.weather_code?.[item.index]).label,
    temperature: Math.round(data.hourly.temperature_2m?.[item.index]),
    rain: Math.round(data.hourly.precipitation_probability?.[item.index] ?? 0),
    wind: Math.round(data.hourly.wind_speed_10m?.[item.index] ?? 0)
  }))
  const currentMeta = weatherMeta(current.weather_code)
  return { location: t(activeLocation.value.name), loading: weatherLoading.value, located: locationDetected.value, icon: currentMeta.icon, label: currentMeta.label, temperature: Math.round(current.temperature_2m), feelsLike: Math.round(current.apparent_temperature), humidity: Math.round(current.relative_humidity_2m), wind: Math.round(current.wind_speed_10m), hourly }
})

function navigate(destination) {
  if (props.mobile && ['operations', 'credentials', 'mobile-connection'].includes(destination)) return
  mailRequestId++
  mailLoading.value = false
  currentMailPage.value = 0; mailVersion = ''
  page.value = destination
  if (destination === 'weather') {
    if (!rawWeather.value) fetchWeather(); else fetchRainAnalysis()
  } else if (destination === 'home') {
    fetchSummaries(true)
  } else if (destination === 'mail') {
    mailChannel.value = 'ALL'
    mailView.value = 'INBOX'
    fetchSummaries()
    syncOutlook()
  } else if (destination === 'social-trends') {
    fetchTrends(false)
  } else if (destination === 'world-news') {
    fetchUsNews(false)
  } else if (destination === 'hotspot-center') {
    fetchTrends(false)
    fetchUsNews(false)
  } else if (destination === 'watch-center') {
    fetchWatch(false)
  }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function selectMailChannel(channel) {
  currentMailPage.value = 0; mailVersion = ''
  mailChannel.value = channel
  if (channel === 'OUTLOOK') loadOutlookStatus()
  fetchSummaries()
}

function selectMailView(view) {
  mailRequestId++
  currentMailPage.value = 0; mailVersion = ''
  mailLoading.value = false
  mailView.value = view
  fetchSummaries()
}

function mailParams() {
  const home = page.value === 'home'
  return { tab: 'EMAIL', excludeAd: blockAd.value,
    view: home ? 'INBOX' : mailView.value,
    source: !home && mailChannel.value !== 'ALL' ? mailChannel.value : undefined,
    q: home ? undefined : searchText.value.trim() || undefined,
    hours: home ? undefined : searchHours.value,
    starred: !home && searchStarred.value ? true : undefined }
}
async function fetchSummaries(silent = false, append = false) {
  if (page.value === 'mail' && mailView.value === 'PLAN') return
  if (silent && mailLoading.value) return
  const requestId = ++mailRequestId
  if (!silent) mailLoading.value = true
  try {
    const params = mailParams()
    const revision = (await axios.get('/api/mails/revision', { params })).data
    if (disposed || requestId !== mailRequestId) return
    const home = page.value === 'home'
    if (home || (page.value === 'mail' && mailChannel.value === 'ALL' && mailView.value === 'INBOX' && !searchText.value && !searchStarred.value && searchHours.value === 120)) mailCount.value = revision.totalElements
    if (home) return
    if (silent && revision.version === mailVersion) return
    const nextPage = append && mailVersion === revision.version ? currentMailPage.value + 1 : 0
    const response = await axios.get('/api/mails/summaries', { params: { ...params, page: nextPage, size: 20 } })
    if (disposed || requestId !== mailRequestId) return
    summaries.value = nextPage ? [...new Map([...summaries.value, ...response.data.content].map(m => [m.id, m])).values()] : response.data.content || []
    currentMailPage.value = nextPage
    mailVersion = revision.version
    mailTotal.value = response.data.totalElements
  } catch { if (!silent) ElMessage.error(t('邮件加载失败，请确认后端服务正在运行')) }
  finally { if (requestId === mailRequestId) mailLoading.value = false }
}

async function loadOutlookStatus() {
  if (props.mobile) return outlookStatus.value
  try { outlookStatus.value = (await axios.get('/api/outlook/status')).data }
  catch { outlookStatus.value = { connected: false, configured: false, desktopState: 'unavailable' } }
  return outlookStatus.value
}

function loadMoreMail() {
  if (!mailLoading.value) fetchSummaries(false, true)
}

async function syncOutlook(force = false) {
  if (props.mobile) return
  if (outlookSyncing.value || (!force && Date.now() - mailboxOpeningSync < 30000)) return
  mailboxOpeningSync = Date.now()
  outlookSyncing.value = true
  try {
    // A disconnected status must not prevent an attempt to reconnect.
    outlookStatus.value = (await axios.post('/api/outlook/refresh', {}, { timeout: 110000 })).data
  } catch {
    await loadOutlookStatus()
  } finally {
    outlookSyncing.value = false
    if (page.value === 'home' || page.value === 'mail') await fetchSummaries(true)
  }
}
async function refreshMailbox() {
  if (props.mobile) return fetchSummaries()
  if (page.value !== 'mail') return fetchSummaries()
  await syncOutlook(true)
  await fetchSummaries()
}

async function openMailDetail(item) {
  const requestId = ++detailRequestId
  insightRequestId++
  mailInsightLoading.value = false
  mailInsightResult.value = null
  mailInsightError.value = ''
  mailDetailVisible.value = true
  mailDetailLoading.value = true
  selectedMail.value = item
  try {
    const detail = (await axios.get(`/api/mails/${item.id}`)).data
    if (!disposed && requestId === detailRequestId && mailDetailVisible.value) selectedMail.value = detail
  } catch (error) {
    if (!disposed && requestId === detailRequestId) ElMessage.error(t('完整邮件加载失败'))
  } finally { if (requestId === detailRequestId) mailDetailLoading.value = false }
}

function closeMailDetail() {
  detailRequestId++
  insightRequestId++
  mailInsightLoading.value = false
  mailInsightResult.value = null
  mailInsightError.value = ''
  mailDetailLoading.value = false
  mailDetailVisible.value = false
  selectedMail.value = null
}

function mailActionLabel(status) {
  return ({ REQUIRED: t('需要处理'), OPTIONAL: t('可自愿选择'), NONE: t('无需立即处理'), UNCLEAR: t('邮件未说明') })[status] || t('待核对')
}
async function analyzeSelectedMail() {
  const mailId = selectedMail.value?.id
  if (!mailId || mailDetailLoading.value || mailInsightLoading.value) return
  const requestId = ++insightRequestId
  mailInsightLoading.value = true
  mailInsightError.value = ''
  try {
    const { data } = await axios.post(`/api/mails/${mailId}/analysis`, null, { timeout: 195000 })
    if (!disposed && requestId === insightRequestId && selectedMail.value?.id === mailId && mailDetailVisible.value) mailInsightResult.value = data
  } catch (error) {
    if (!disposed && requestId === insightRequestId && selectedMail.value?.id === mailId && mailDetailVisible.value)
      mailInsightError.value = error.response?.status === 422 ? '这封邮件的正文尚未同步，请刷新邮件后重试。' : '本机 AI 暂时无法完成分析，请稍后重试。'
  } finally {
    if (requestId === insightRequestId) mailInsightLoading.value = false
  }
}

async function toggleMailStar(item) {
  const nextValue = !item.starred
  try {
    const updated = (await axios.patch(`/api/mails/${item.id}/star`, { value: nextValue })).data
    const index = summaries.value.findIndex(mail => mail.id === item.id)
    if (index >= 0) {
      if (mailView.value === 'STARRED' && !updated.starred) summaries.value.splice(index, 1)
      else summaries.value[index] = { ...summaries.value[index], ...updated }
    }
    if (selectedMail.value?.id === item.id) selectedMail.value = { ...selectedMail.value, ...updated }
    ElMessage.success(nextValue ? t('已添加本地星标') : t('已取消本地星标'))
  } catch (error) {
    console.error(error)
    ElMessage.error(t('星标状态更新失败'))
  }
}

async function markMailRead(item) {
  try {
    const updated = (await axios.patch(`/api/mails/${item.id}/read`, { value: true })).data
    mailPlanRevision.value++
    if (['INBOX', 'SNOOZED'].includes(mailView.value)) {
      const count = summaries.value.length
      summaries.value = summaries.value.filter(mail => mail.id !== item.id)
      if (count !== summaries.value.length) mailTotal.value = Math.max(0, mailTotal.value - 1)
    }
    else {
      const index = summaries.value.findIndex(mail => mail.id === item.id)
      if (index >= 0) summaries.value[index] = { ...summaries.value[index], ...updated }
    }
    if (!item.inboxRead) mailCount.value = Math.max(0, mailCount.value - 1)
    if (selectedMail.value?.id === item.id) closeMailDetail()
    ElMessage.success(t('已从 Smart Inbox 收件箱移除，原邮箱状态没有改变'))
  } catch (error) {
    console.error(error)
    ElMessage.error(t('已读状态更新失败'))
  }
}

function readableMailContent(content) {
  const value = String(content || '').trim()
  if (!value) return ''
  if (!/<[a-z][\s\S]*>/i.test(value)) return value
  const withBreaks = value
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<\/(p|div|li|tr|h[1-6])>/gi, '\n')
  const documentNode = new DOMParser().parseFromString(withBreaks, 'text/html')
  return String(documentNode.body.textContent || '')
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

async function fetchUsNews(refresh = false) {
  usNewsLoading.value = true
  try {
    const response = await axios.get('/api/dashboard/us-news', { params: { refresh } })
    worldNews.value = Array.isArray(response.data?.combined) ? response.data.combined : []
    savePreview('news', worldNews.value.slice(0, 2))
    usNewsSources.value = Array.isArray(response.data?.sources) ? response.data.sources : []
  } catch (error) { console.warn('Failed to load U.S. news sources', error) }
  finally { usNewsLoading.value = false }
}

async function fetchTrends(refresh = false) {
  trendsLoading.value = true
  try {
    const response = await axios.get('/api/dashboard/trends', { params: { refresh } })
    trendPlatforms.value = Array.isArray(response.data) ? response.data : []
    savePreview('trends', trendPlatforms.value.map(p => ({ ...p, items: (p.items || []).slice(0, 2) })))
  } catch (error) { console.warn('Failed to load social trends', error) }
  finally { trendsLoading.value = false }
}

async function refreshHotspotCenter() {
  await Promise.all([fetchTrends(true), fetchUsNews(true)])
}

async function fetchWatch(refresh = false) {
  watchLoading.value = true
  try {
    const response = await axios.get('/api/dashboard/watch', { params: { refresh } })
    watchData.value = {
      movies: Array.isArray(response.data?.movies) ? response.data.movies : [],
      tvShows: Array.isArray(response.data?.tvShows) ? response.data.tvShows : [],
      sources: Array.isArray(response.data?.sources) ? response.data.sources : [],
      updatedAt: response.data?.updatedAt || null
    }
    savePreview('watch', { movies: watchData.value.movies.slice(0, 1), tvShows: watchData.value.tvShows.slice(0, 1) })
  } catch (error) { console.warn('Failed to load watch center', error) }
  finally { watchLoading.value = false }
}

async function fetchWeather() {
  const requestId = ++weatherRequestId
  rainRequestId++; rainAnalysis.value = null
  weatherLoading.value = true
  let loaded = false
  try {
    const { latitude, longitude } = activeLocation.value
    const data = (await axios.get('/api/dashboard/weather', { params: { latitude, longitude } })).data
    if (disposed || requestId !== weatherRequestId) return
    rawWeather.value = data
    savePreview('weather', { location: locationKey.value, data })
    loaded = true
  } catch (error) { console.warn('Failed to load weather', error) }
  finally { if (requestId === weatherRequestId) weatherLoading.value = false }
  if (loaded && page.value === 'weather') fetchRainAnalysis()
}

async function fetchRainAnalysis() {
  const hours = weatherView.value.hourly.map(({ time, temperature, rain, label }) => ({ time, temperature, rain, label }))
  if (!hours.length) return
  const requestId = ++rainRequestId
  rainAnalysisLoading.value = true
  rainAnalysis.value = null
  try {
    const data = (await axios.post('/api/dashboard/weather/analysis', { location: t(activeLocation.value.name), hours })).data
    if (!disposed && requestId === rainRequestId) rainAnalysis.value = data
  } catch (error) { console.warn('Failed to analyze rain forecast', error) }
  finally { if (requestId === rainRequestId) rainAnalysisLoading.value = false }
}

function changeLocation(key) {
  locationKey.value = key
  if (key !== 'detected') localStorage.setItem('smart-inbox.weather-location', key)
  fetchWeather()
}

function detectLocation() {
  if (locating.value) return
  if (!navigator.geolocation) { fetchWeather(); return }
  locating.value = true
  navigator.geolocation.getCurrentPosition(position => {
    if (disposed) return
    const latitude = position.coords.latitude
    const longitude = position.coords.longitude
    const nearest = locations.map(item => ({ ...item, distance: distanceKm(latitude, longitude, item.latitude, item.longitude) })).sort((a, b) => a.distance - b.distance)[0]
    if (nearest && nearest.distance <= 80) {
      locationKey.value = nearest.key
      localStorage.setItem('smart-inbox.weather-location', nearest.key)
    } else {
      detectedLocation.value = { key: 'detected', name: '当前位置', latitude, longitude }
      locationKey.value = 'detected'
    }
    locationDetected.value = true
    locating.value = false
    fetchWeather()
  }, () => {
    if (disposed) return
    locating.value = false
    fetchWeather()
  }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 })
}

function distanceKm(lat1, lon1, lat2, lon2) {
  const toRad = value => value * Math.PI / 180
  const dLat = toRad(lat2 - lat1)
  const dLon = toRad(lon2 - lon1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

function weatherMeta(code) {
  if (code === 0) return { icon: '☀️', label: t('晴朗') }
  if ([1, 2].includes(code)) return { icon: '🌤️', label: t('晴间多云') }
  if (code === 3) return { icon: '☁️', label: t('多云') }
  if ([45, 48].includes(code)) return { icon: '🌫️', label: t('有雾') }
  if ([51, 53, 55, 56, 57].includes(code)) return { icon: '🌦️', label: t('毛毛雨') }
  if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return { icon: '🌧️', label: t('有雨') }
  if ([71, 73, 75, 77, 85, 86].includes(code)) return { icon: '🌨️', label: t('有雪') }
  if ([95, 96, 99].includes(code)) return { icon: '⛈️', label: t('雷暴') }
  return { icon: '🌥️', label: t('天气变化') }
}

function avatarText(subject) { return String(subject || 'M').trim().slice(0, 1).toUpperCase() }
function formatTime(value) {
  if (!value) return ''
  const date = Array.isArray(value) ? new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0) : new Date(value)
  return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat(dateLocale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date) : ''
}
function formatSource(source) { return ({ GMAIL: 'Gmail', QQMAIL: t('QQ 邮箱'), 'Google Mail': 'Gmail', 'QQ Mail': t('QQ 邮箱') })[source] || source || t('邮件') }

async function snoozeMail(item) {
  const defaultDate = new Date(Date.now() + 3600000)
  const localDate = new Date(defaultDate.getTime() - defaultDate.getTimezoneOffset()*60000).toISOString().slice(0,16)
  try {
    const { value } = await ElMessageBox.prompt(t('选择提醒时间（本地时间）。到时会重新出现在收件箱。'), t('稍后提醒'), { inputType: 'datetime-local', inputValue: localDate, confirmButtonText: t('保存提醒'), cancelButtonText: t('取消'), inputValidator: value => Number.isFinite(new Date(value).getTime()) && new Date(value).getTime() > Date.now() || t('请选择未来时间') })
    await axios.patch('/api/mails/' + item.id + '/snooze', { until: new Date(value).getTime() })
    closeMailDetail(); mailVersion = ''; await fetchSummaries(); ElMessage.success(t('已安排稍后提醒'))
  } catch(error) { if(error !== 'cancel' && error !== 'close') ElMessage.error(t('提醒保存失败')) }
}
async function cancelSnooze(item) {
  try { await axios.patch('/api/mails/' + item.id + '/snooze', { until: null }); mailVersion=''; await fetchSummaries(); }
  catch { ElMessage.error(t('无法取消提醒')) }
}
async function enableNotifications() {
  if (reminderSettingBusy.value) return
  reminderSettingBusy.value = true
  try {
    const enabled = !remindersEnabled.value
    let permission = 'denied'
    if (enabled && 'Notification' in window) {
      try { permission = await Notification.requestPermission() } catch { /* Use in-app reminders. */ }
    }
    await axios.put('/api/dashboard/preferences', { remindersEnabled: String(enabled) })
    remindersEnabled.value = enabled
    ElMessage.success(!enabled ? t('已关闭邮件提醒') : permission === 'granted'
      ? t('已启用桌面与应用内提醒；关闭程序或待机时暂停，恢复后补提醒')
      : t('已启用应用内提醒；未获得桌面通知权限，回到应用时显示提醒'))
    if (enabled) pollReminders()
  } catch { ElMessage.error(t('提醒设置保存失败，请重试')) }
  finally { reminderSettingBusy.value = false }
}
async function pollReminders() {
  if (document.hidden || disposed || reminderBusy || !remindersEnabled.value) return
  reminderBusy = true
  try {
    const rows = (await axios.get('/api/mails/reminders')).data
    if (disposed || !remindersEnabled.value) return
    for (const row of rows) {
      const key = row.id + ':' + row.snoozedUntil
      if (notifiedInPage.has(key)) continue
      notifiedInPage.add(key)
      ElNotification({ title: t('邮件稍后提醒'), message: row.subject, duration: 10000, onClick: () => openMailDetail(row) })
      if ('Notification' in window && Notification.permission === 'granted') { const n=new Notification(t('Smart Inbox · 邮件提醒'), { body: row.subject, tag: key }); n.onclick=()=>{window.focus();openMailDetail(row);n.close()} }
      try { await axios.post('/api/mails/' + row.id + '/reminder-ack', { until: row.snoozedUntil }) }
      catch { notifiedInPage.delete(key) }
    }
  } catch { /* Retain reminders for the next successful attempt. */ }
  finally { reminderBusy = false }
}
async function loadPreferences() {
  try {
    const settings = (await axios.get('/api/dashboard/preferences')).data
    if(settings.weatherLocation) locationKey.value=settings.weatherLocation
    if(settings.mailBlockAd) blockAd.value=settings.mailBlockAd==='true'
    remindersEnabled.value=settings.remindersEnabled==='true'
  } catch { /* Existing local city is the offline fallback. */ }
  reminderSettingsReady=true
}
async function onRestored() { await loadPreferences(); await refreshTasks({ force: true }); mailVersion=''; fetchSummaries(true); fetchWeather() }
function onVisibility() { if (!document.hidden) { if (['mail','home'].includes(page.value)) fetchSummaries(true); pollReminders() } }
watch([blockAd, searchHours, searchStarred], () => { mailVersion=''; if (page.value === 'mail') fetchSummaries() })
watch(blockAd, value => { if(reminderSettingsReady) axios.put('/api/dashboard/preferences', {mailBlockAd:String(value)}).catch(()=>{}) })
watch(locationKey, value => { if(reminderSettingsReady && value!=='detected') axios.put('/api/dashboard/preferences', {weatherLocation:value}).catch(()=>{}) })
onMounted(async () => {
  removeFocusShortcut = installFocusShortcut({ window, document, controller: focusController, isEnabled: focusShortcutEnabled,
    onPending: () => showFocusShortcutNotice({ message: '正在切换计时…' }), onResult: showFocusShortcutNotice })
  trendPlatforms.value=readPreview('trends') || []
  worldNews.value=readPreview('news') || []
  watchData.value=readPreview('watch') || watchData.value
  const cachedWeather=readPreview('weather', 10*60000)
  if(cachedWeather?.location === locationKey.value) rawWeather.value=cachedWeather.data
  fetchSummaries(true); loadOutlookStatus()
  mailPollTimer=setInterval(() => { if (!document.hidden && ['home','mail'].includes(page.value)) {fetchSummaries(true);loadOutlookStatus()} },30000)
  clockTimer=setInterval(() => {now.value=new Date()},30000)
  reminderTimer=setInterval(pollReminders,60000)
  document.addEventListener('visibilitychange',onVisibility)
  window.addEventListener('smart-inbox:mobile-resume',onVisibility)
  await loadPreferences()
  if(disposed) return
  detectLocation(); pollReminders()
})
onBeforeUnmount(() => {
  removeFocusShortcut?.(); focusController.dispose(); clearTimeout(focusNoticeTimer)
  disposed=true; mailRequestId++; weatherRequestId++; rainRequestId++; detailRequestId++; insightRequestId++
  clearInterval(mailPollTimer); clearInterval(clockTimer); clearInterval(reminderTimer)
  document.removeEventListener('visibilitychange',onVisibility)
  window.removeEventListener('smart-inbox:mobile-resume',onVisibility)
})
</script>

<style scoped>
.focus-shortcut-notice { position: fixed; z-index: 2100; top: calc(22px + env(safe-area-inset-top)); left: 50%; transform: translateX(-50%); width: max-content; max-width: calc(100vw - 40px); box-sizing: border-box; padding: 13px 20px; border: 1px solid #dce4ed; border-radius: 14px; background: #fff; color: #263b55; box-shadow: 0 8px 30px #233b5733; font-size: 14px; font-weight: 700; pointer-events: none; }
.focus-shortcut-notice.effective { border-color: #ff0000; background: #ff0000; color: #230000; }
.focus-shortcut-notice.ineffective { border-color: #008000; background: #008000; color: #fff; }
.mobile-online { display: flex; align-items: center; gap: 8px; padding: calc(10px + env(safe-area-inset-top)) max(18px, env(safe-area-inset-right)) 10px max(18px, env(safe-area-inset-left)); background: #eaf2ee; color: #42675a; font-size: 11px; }
.mobile-online > span { width: 6px; height: 6px; background: #53a57f; border-radius: 50%; }
.mail-search{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:0 0 20px;color:#647187;font-size:13px}.mail-search form{display:flex;gap:8px;flex:1;min-width:240px}.mail-search input:not([type=checkbox]){flex:1;min-width:100px}.mail-search input,.mail-search select,.mail-search button{border:1px solid #dbe4ef;border-radius:10px;padding:9px 12px;background:#fff;color:#315781}.inbox-tabs{flex-wrap:wrap}
.mail-sync-status{display:flex;flex-wrap:wrap;gap:16px;margin:0 0 16px;color:#73839a;font-size:12px}
.workspace-shell { min-height: 100vh; color: #1c2739; background: radial-gradient(circle at 0 0, rgba(122,184,255,.28), transparent 30rem), linear-gradient(145deg,#eef4fa,#e8edf4); }
.workspace-header { position: sticky; top: 0; z-index: 20; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; min-height: 78px; padding: 12px clamp(16px,4vw,56px); border-bottom: 1px solid rgba(255,255,255,.76); background: rgba(246,249,252,.76); box-shadow: 0 8px 28px rgba(57,70,94,.06); backdrop-filter: blur(22px); }
.home-button { justify-self: start; border: 1px solid rgba(99,120,151,.18); border-radius: 13px; padding: 10px 14px; color: #2766c7; font-weight: 800; background: rgba(255,255,255,.76); box-shadow: 0 6px 18px rgba(59,77,105,.07); }
.home-button-icon { margin-right: 4px; font-size: 22px; line-height: 0; vertical-align: -2px; }
.page-identity { display: grid; justify-items: center; }
.page-identity span { color: #8d98a9; font-size: 9px; font-weight: 900; letter-spacing: .16em; }
.page-identity strong { font-size: 18px; }
.header-status { display: flex; justify-self: end; align-items: center; gap: 7px; color: #7d8798; font-size: 11px; }
.online-dot { width: 7px; height: 7px; border-radius: 50%; background: #21b46b; box-shadow: 0 0 0 4px rgba(33,180,107,.11); }
.workspace-content { min-height: calc(100vh - 78px); padding: clamp(24px,4vw,52px); }
.workspace-content:has(.focus-center[data-focus-state="INEFFECTIVE"]) { background: #008000; --focus-state-ink: #fff; }
.workspace-content:has(.focus-center[data-focus-state="EFFECTIVE"]) { background: #ff0000; --focus-state-ink: #230000; }
.mail-workspace, .credentials-workspace { width: min(1120px, 100%); margin: 0 auto; }
.mail-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 22px; }
.mail-source-notice { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: -6px 0 20px; padding: 16px 18px; border: 1px solid #f0d9a8; border-radius: 18px; background: rgba(255,248,230,.9); color: #6f5b31; }
.mail-source-notice div { display: grid; gap: 4px; }
.mail-source-notice strong { font-size: 14px; color: #74571c; }
.mail-source-notice span { font-size: 12px; line-height: 1.55; }
.mail-source-notice button { flex-shrink: 0; border: 0; border-radius: 11px; padding: 9px 13px; color: #fff; font-size: 12px; font-weight: 800; background: #c68a22; }
.mail-filter-stack { display: grid; gap: 9px; }
.inbox-tabs { display: flex; width: fit-content; gap: 5px; padding: 4px; border-radius: 13px; background: rgba(30,48,78,.08); }
.inbox-tabs button { border: 0; border-radius: 9px; padding: 7px 13px; color: #778396; font-size: 11px; font-weight: 800; background: transparent; }
.inbox-tabs button.active { color: #fff; background: #253b61; box-shadow: 0 5px 14px rgba(31,48,78,.18); }
.mail-tabs { display: flex; gap: 5px; padding: 5px; border: 1px solid rgba(255,255,255,.84); border-radius: 15px; background: rgba(255,255,255,.58); }
.mail-tabs button { border: 0; border-radius: 11px; padding: 9px 16px; color: #647187; font-size: 13px; font-weight: 750; background: transparent; }
.mail-tabs button.active { color: #1d5fc9; background: #fff; box-shadow: 0 5px 16px rgba(55,75,110,.1); }
.mail-actions { display: flex; align-items: center; gap: 12px; }
.mail-list-footer { display:flex; justify-content:center; align-items:center; gap:18px; padding:24px; color:#8799b1; font-size:12px; }
.mail-list-footer button { border:0; border-radius:12px; padding:10px 18px; color:#2867cc; background:#fff; font-weight:700; }
.refresh-button { border: 0; border-radius: 12px; padding: 10px 16px; color: #fff; font-size: 12px; font-weight: 800; background: linear-gradient(145deg,#438aff,#2163db); }
.mail-list { display: grid; gap: 13px; }
.mail-card { padding: 19px 21px 14px; border: 1px solid rgba(255,255,255,.9); border-radius: 22px; background: rgba(255,255,255,.82); box-shadow: 0 12px 32px rgba(51,65,90,.075); cursor: pointer; transition: transform .18s, box-shadow .18s; }
.mail-card:hover { transform: translateY(-2px); box-shadow: 0 18px 38px rgba(51,65,90,.12); }
.mail-card:focus-visible { outline: 3px solid rgba(48,112,211,.25); outline-offset: 2px; }
.mail-card-head { display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 13px; }
.sender-avatar { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 13px; color: #fff; font-weight: 900; background: linear-gradient(145deg,#6ea7ff,#3474df); }
.mail-title { min-width: 0; }
.mail-title h3 { overflow: hidden; margin: 0; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.mail-title span { color: #9aa4b3; font-size: 10px; }
.mail-card-side { display: grid; justify-items: end; gap: 9px; }
.mail-tags { display: flex; gap: 7px; }
.mail-tags span, .ignored { padding: 5px 8px; border-radius: 8px; font-size: 10px; font-weight: 800; }
.urgency { color: #308a57; background: #e6f7ed; }
.u-5, .u-6 { color: #c64343; background: #ffebeb; }
.u-3, .u-4 { color: #b77719; background: #fff3d8; }
.category { color: #2f70d3; background: #e8f1ff; }
.mail-quick-actions { display: flex; gap: 6px; }
.mail-quick-actions button { border: 1px solid #dce4ef; border-radius: 9px; padding: 6px 9px; color: #758196; font-size: 11px; font-weight: 800; background: rgba(255,255,255,.8); }
.mail-quick-actions button:first-child { min-width: 32px; color: #a98428; font-size: 17px; line-height: 1; }
.mail-quick-actions button.active { border-color: #f0d17a; color: #d49200; background: #fff7dc; }
.local-read-state { align-self: center; color: #8995a8; font-size: 11px; font-weight: 800; }
.mail-card > p { margin: 15px 0; color: #5f6c81; font-size: 13px; line-height: 1.65; }
.mail-card footer { display: flex; justify-content: space-between; padding-top: 11px; border-top: 1px solid #e9edf3; color: #9aa3b2; font-size: 10px; }
.ignored { color: #7b8493; background: #edf0f4; }
.center-state { display: grid; min-height: 430px; place-items: center; align-content: center; gap: 8px; color: #7b8799; }
.center-state .el-icon { font-size: 34px; }
.empty-symbol { font-size: 45px; }
.center-state small { color: #a1a9b5; }
.mail-detail-backdrop { position: fixed; z-index: 1000; inset: 0; display: grid; place-items: center; padding: 24px; background: rgba(20,30,46,.35); backdrop-filter: blur(12px); }
.mail-detail-sheet { overflow-y: auto; width: min(880px,100%); max-height: min(820px,calc(100vh - 48px)); border: 1px solid rgba(255,255,255,.92); border-radius: 30px; background: rgba(249,251,254,.98); box-shadow: 0 34px 90px rgba(23,36,57,.28); }
.mail-detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 27px 30px 23px; color: #fff; background: linear-gradient(135deg,#1d2f4d,#142138); }
.mail-detail-header span { color: #9eb5d8; font-size: 9px; font-weight: 900; letter-spacing: .15em; }
.mail-detail-header h2 { max-width: 720px; margin: 6px 0 0; font-size: clamp(22px,3vw,32px); line-height: 1.25; letter-spacing: -.025em; }
.mail-detail-header button { flex: 0 0 auto; display: grid; width: 36px; height: 36px; place-items: center; border: 0; border-radius: 50%; color: #dce8fb; font-size: 27px; line-height: 1; background: rgba(255,255,255,.1); }
.mail-detail-loading { display: grid; min-height: 360px; place-items: center; align-content: center; gap: 12px; color: #758196; }
.mail-detail-meta { display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 13px; padding: 21px 30px 12px; }
.sender-avatar.large { width: 50px; height: 50px; border-radius: 16px; font-size: 18px; }
.mail-detail-meta > div:nth-child(2) { display: grid; gap: 4px; min-width: 0; }
.mail-detail-meta strong { overflow: hidden; color: #27344a; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.mail-detail-meta small { color: #9aa4b3; font-size: 10px; }
.mail-detail-actions { display: flex; gap: 9px; padding: 5px 30px 16px; }
.mail-detail-actions button { border: 1px solid #dbe4f0; border-radius: 11px; padding: 9px 13px; color: #657288; font-size: 11px; font-weight: 850; background: #fff; }
.mail-detail-actions button.active { border-color: #efd075; color: #b97900; background: #fff7dc; }
.mail-detail-actions .read-action { border-color: transparent; color: #fff; background: linear-gradient(145deg,#42b982,#239d67); }
.mail-detail-actions .read-action:disabled { cursor: default; opacity: .62; }
.mail-insight-card { margin: 0 30px 14px; padding: 17px 20px; border: 1px solid #dce9fb; border-radius: 18px; background: #eef5ff; }
.mail-insight-heading { display: flex; justify-content: space-between; align-items: center; gap: 18px; }
.mail-insight-heading span,.mail-full-content > span { color: #3d6fad; font-size: 9px; font-weight: 900; letter-spacing: .12em; }
.mail-insight-heading h3 { margin: 4px 0 0; color: #254b7b; font-size: 16px; }
.mail-insight-heading button { flex: 0 0 auto; border: 0; border-radius: 11px; padding: 9px 14px; background: linear-gradient(145deg,#4289ed,#2867d5); color: white; font-size: 11px; font-weight: 850; }
.mail-insight-heading button:disabled { opacity: .65; cursor: wait; }
.mail-insight-help,.mail-insight-error { margin: 10px 0 0; color: #5d6f89; font-size: 12px; line-height: 1.6; }
.mail-insight-error { color: #ac4b40; }
.mail-insight-report { display: grid; gap: 15px; margin-top: 18px; padding-top: 16px; border-top: 1px solid #d7e5f7; }
.mail-insight-section strong { display: inline-block; margin-right: 9px; color: #2a568b; font-size: 12px; }
.mail-insight-section p,.mail-insight-section li { color: #354c69; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.mail-insight-section p { margin: 6px 0 0; white-space: pre-line; }
.mail-insight-section ul { margin: 7px 0 0; padding-left: 19px; }
.mail-insight-section li + li { margin-top: 4px; }
.mail-action-status { display: inline-block; border-radius: 999px; padding: 3px 8px; color: #6b788b; font-size: 10px; font-weight: 800; background: #e0e9f7; }
.mail-action-status.required { color: #af4e36; background: #fff0e8; }.mail-action-status.optional { color: #7b6425; background: #fff5d8; }.mail-action-status.none { color: #238364; background: #e1f5ec; }
.mail-insight-scope,.mail-insight-report small { margin: 0; color: #77869c; font-size: 10px; line-height: 1.5; }
.mail-full-content { overflow-y: auto; max-height: 390px; margin: 0 30px 30px; padding: 19px 21px; border: 1px solid #e3e8ef; border-radius: 18px; background: #fff; }
.mail-full-content > p { margin: 10px 0 0; color: #303b4e; font-size: 13px; line-height: 1.78; white-space: pre-wrap; overflow-wrap: anywhere; }
.mail-full-content small { display: block; margin-top: 15px; padding-top: 12px; border-top: 1px solid #edf0f4; color: #9a7b42; font-size: 10px; line-height: 1.5; }
@media (max-width: 760px) {
  .workspace-content { padding: 22px 14px; }
  .mail-toolbar { align-items: stretch; flex-direction: column; }
  .mail-source-notice { align-items: flex-start; flex-direction: column; }
  .mail-filter-stack { overflow-x: auto; }
  .mail-actions { justify-content: space-between; flex-wrap: wrap; }
  .mail-card-head { grid-template-columns: auto 1fr; }
  .mail-card-side { grid-column: 2; justify-items: start; }
  .mail-detail-backdrop { padding: 10px; }
  .mail-detail-sheet { max-height: calc(100vh - 20px); border-radius: 22px; }
  .mail-detail-header,.mail-detail-meta { padding-right: 18px; padding-left: 18px; }
  .mail-detail-meta { grid-template-columns: auto 1fr; }
  .mail-detail-meta .mail-tags { grid-column: 2; }
  .mail-detail-actions,.mail-insight-card,.mail-full-content { margin-right: 18px; margin-left: 18px; }
  .mail-insight-heading { align-items: flex-start; }
  .mail-detail-actions { padding-right: 0; padding-left: 0; flex-wrap: wrap; }
}
/* One compact navigation bar owns the safe area on phone subpages. */
@media (max-width: 760px), (max-width: 960px) and (pointer: coarse), (max-width: 960px) and (max-height: 500px) {
  .workspace-header { grid-template-columns: 44px minmax(0, 1fr) 44px; gap: 8px; min-height: calc(64px + env(safe-area-inset-top)); padding: calc(10px + env(safe-area-inset-top)) max(12px, env(safe-area-inset-right)) 10px max(12px, env(safe-area-inset-left)); background: rgba(239,245,251,.94); border-bottom-color: rgba(117,139,170,.12); box-shadow: none; }
  .home-button { display: grid; place-items: center; width: 44px; height: 44px; padding: 0; border: 0; border-radius: 12px; background: transparent; box-shadow: none; }
  .home-button:active { background: rgba(39,102,199,.08); }
  .home-button:focus-visible { outline: 2px solid #2766c7; outline-offset: 2px; }
  .home-button-icon { margin: 0; font-size: 30px; line-height: 1; }
  .home-button-label, .page-identity > span { display: none; }
  .page-identity { min-width: 0; justify-items: center; text-align: center; }
  .page-identity strong { max-width: 100%; font-size: 17px; line-height: 1.3; overflow-wrap: anywhere; }
  .header-status { justify-content: center; width: 44px; height: 44px; }
  .header-status-label { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .workspace-content { min-height: calc(100dvh - 64px - env(safe-area-inset-top)); padding-top: 16px; padding-left: max(12px, env(safe-area-inset-left)); padding-right: max(12px, env(safe-area-inset-right)); }
}
</style>

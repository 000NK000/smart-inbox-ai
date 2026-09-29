<template>
  <section class="operations">
    <header><div><small>SYSTEM & DATA</small><h1>{{ t('运行状态与备份') }}</h1><p>{{ t('查看收信、AI 与数据源状态，或备份你的个人记录。') }}</p></div><button :disabled="loading" @click="refresh">{{ t('刷新状态') }}</button></header>
    <p v-if="error" class="notice" role="alert">{{ t(error) }}</p>
    <div class="metrics">
      <article><small>{{ t('本地模型') }}</small><h3>{{ data.model?.name || t('等待连接') }}</h3><p>{{ t(modelState) }}</p><p v-if="data.model?.vramBytes">{{ t('模型显存') }} {{ (data.model.vramBytes/1024**3).toFixed(1) }} GB</p></article>
      <article><small>{{ t('AI 调度') }}</small><h3>{{ data.ai?.running || 0 }} {{ t('执行 ·') }} {{ data.ai?.queued || 0 }} {{ t('排队') }}</h3><p>{{ t('并发') }} {{ data.ai?.concurrency || 1 }} {{ t('· 队列上限') }} {{ data.ai?.capacity || 32 }}</p><p>{{ t('合并重复请求') }} {{ data.ai?.deduplicated || 0 }} {{ t('次') }}</p></article>
      <article><small>{{ t('待分析邮件') }}</small><h3>{{ data.pendingAnalysis || 0 }} {{ t('封') }}</h3><p>{{ t('尚未获得 AI 摘要的邮件') }}</p><button :disabled="busy.analysis || data.repairing || !data.pendingAnalysis" @click="retryAnalysis">{{ data.repairing ? t('重试处理中…') : t('重试 AI 摘要') }}</button></article>
      <article><small>{{ t('邮件任务规划') }}</small><h3>{{ data.taskPlan?.analyzedMails || 0 }} / {{ data.taskPlan?.totalMails || 0 }}</h3><p>{{ t('待处理') }} {{ data.taskPlan?.pendingMails || 0 }} {{ t('· 失败') }} {{ data.taskPlan?.failedCount || 0 }}</p><button :disabled="busy.plan || data.taskPlan?.running" @click="retryPlan">{{ t('继续分析 / 重试') }}</button></article>
    </div>
    <article class="panel"><h2>{{ t('邮件同步') }}</h2><div v-for="source in channels" :key="source.source" class="source"><div><b>{{ source.source }}</b><p>{{ source.failureCode || source.state }} {{ t('· 最近成功') }} {{ time(source.lastSuccess) }}</p><small>{{ t('本轮扫描') }} {{ source.scanned || 0 }} {{ t('· 获取正文') }} {{ source.bodyFetched || 0 }} {{ t('· 新投递') }} {{ source.published || 0 }}</small></div><button :disabled="source.inFlight || busy[source.source]" @click="retryMail(source.source)">{{ source.inFlight ? t('同步中…') : t('重试此邮箱') }}</button></div><p v-if="!channels.length">{{ t('暂时无法读取采集器状态') }}</p></article>
    <article class="panel"><h2>{{ t('新闻与榜单') }}</h2><p>{{ t('按需加载。尚未打开的模块不会在后台拉取完整榜单；下方显示本次运行已访问的数据源。') }}</p><div v-for="source in data.sources || []" :key="source.group + source.id" class="source"><div><b>{{ sourceName(source.name) }}</b><p>{{ t(source.status) }}</p><small>{{ t('最近数据时间') }} {{ time(source.lastSuccess) }}</small></div><button :disabled="source.inFlight || busy[source.group+source.id]" @click="retrySource(source)">{{ t('重试此来源') }}</button></div><p v-if="!data.sources?.length">{{ t('打开热点中心或追剧中心后可查看各来源状态。') }}</p></article>
    <article class="panel backup"><h2>{{ t('个人数据备份') }}</h2><p>{{ t('包含任务与完成记录、追剧清单、日历与课程安排、求职申请、专注计时、刷题记录、题型心得与单题图文题解，以及邮件的本地已读 / 星标 / 稍后提醒状态和城市设置。备份文件保存到你选择的位置，不包含账号密码、授权令牌或邮件正文。') }}</p><div class="controls"><button :disabled="busy.export" @click="exportBackup">{{ t('下载完整记录备份') }}</button><label class="file-button">{{ t('选择备份验证') }}<input :disabled="busy.restore" type="file" accept=".json,application/json" @change="selectBackup" /></label></div><div v-if="preview" class="restore-preview"><h3>{{ t('备份验证通过') }}</h3><p>{{ preview.tasks }} {{ t('条任务 ·') }} {{ preview.watchlist }} {{ t('条追剧记录 ·') }} {{ preview.calendar || 0 }} {{ t('条日历记录 ·') }} {{ preview.jobApplications || 0 }} {{ t('条求职申请 ·') }} {{ preview.practice || 0 }} {{ t('条刷题记录 ·') }} {{ preview.practiceGroups || 0 }} {{ t('份题型心得 ·') }} {{ preview.practiceSolutions || 0 }} {{ t('份单题题解 ·') }} {{ preview.practiceImages || 0 }} {{ t('张截图 ·') }} {{ preview.mailStates }} {{ t('条邮件状态') }}</p><p>{{ t('可匹配邮件') }} {{ preview.matchedMails }} {{ t('封；当前没有原邮件的') }} {{ preview.missingMails }} {{ t('封将跳过，相应任务仍可恢复但无邮件跳转。') }}</p><p>{{ t(preview.policy) }}</p><button :disabled="busy.restore || busy.validation" @click="restoreBackup">{{ t('确认合并恢复') }}</button><button :disabled="busy.restore" @click="cancelBackup">{{ t('取消') }}</button></div><p class="footnote">{{ t('备份通过 SHA-256 检查内容完整性；它不是加密文件，请妥善保存。恢复前先验证，再执行一次数据库事务。截图会让备份文件变大。') }}</p></article>
  </section>
</template>
<script setup>
import { useI18n } from '../i18n/index.js'
const { t, dateLocale } = useI18n()
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
const emit=defineEmits(['restored'])
const data=ref({}),channels=ref([]),loading=ref(false),error=ref(''),preview=ref(null),backup=ref(null),busy=reactive({})
let timer,disposed=false,validationId=0
const modelState=computed(()=>({loaded:'已加载，可处理 AI 请求',idle:'服务可用，模型空闲 / 未加载',unavailable:'服务暂不可用，请检查 Ollama'}[data.value.model?.state] || '正在确认'))
function sourceName(value) { return String(value || '').split(' · ').map(part => t(part)).join(' · ') }
function time(value){return value ? new Date(value).toLocaleString(dateLocale.value) : t('尚无成功记录')}
async function refresh(){
  if(loading.value || disposed)return
  loading.value=true
  const results=await Promise.allSettled([axios.get('/api/dashboard/operations',{timeout:12000}),axios.get('/api/outlook/sources',{timeout:10000})])
  if(!disposed){if(results[0].status==='fulfilled')data.value=results[0].value.data;if(results[1].status==='fulfilled')channels.value=results[1].value.data.channels || [];error.value=results.some(r=>r.status==='rejected')?'部分状态暂时不可用，已保留最近一次结果。':''}
  loading.value=false
}
async function action(key,fn){if(busy[key])return;busy[key]=true;try{await fn();await refresh()}catch(e){ElMessage.error(t(e.response?.data?.message || e.response?.data?.reason || '操作未完成，请稍后重试'))}finally{busy[key]=false}}
function retryMail(source){return action(source,()=>axios.post('/api/outlook/sources/'+source+'/retry'))}
function retrySource(s){return action(s.group+s.id,()=>axios.post('/api/dashboard/operations/retry/'+s.group+'/'+s.id,{}, {timeout:120000}))}
function retryAnalysis(){return action('analysis',()=>axios.post('/api/dashboard/operations/retry-analysis'))}
function retryPlan(){return action('plan',()=>axios.post('/api/mails/task-plan/retry'))}
async function exportBackup(){return action('export',async()=>{const {data}=await axios.get('/api/dashboard/backup');const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='SmartInbox-backup-'+new Date().toISOString().slice(0,10)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)})}
async function selectBackup(event){
  if(busy.restore)return
  const requestId=++validationId
  busy.validation=true
  preview.value=null;backup.value=null
  try{const file=event.target.files?.[0];if(!file)return;if(file.size>20*1024*1024)throw Error('备份文件不能超过20 MB');const parsed=JSON.parse(await file.text());const {data}=await axios.post('/api/dashboard/backup/validate',parsed);if(!disposed&&requestId===validationId){backup.value=parsed;preview.value=data}}catch(e){if(!disposed&&requestId===validationId)ElMessage.error(t(e.response?.data?.message || e.message || '备份格式或校验不正确，没有修改任何数据'))}finally{if(requestId===validationId){busy.validation=false;event.target.value=''}}
}
async function restoreBackup(){
  if(busy.validation||busy.restore||!preview.value||!backup.value)return
  const validatedBackup=backup.value,requestId=validationId
  busy.restore=true
  try{await ElMessageBox.confirm(t('将合并任务、追剧、日历、求职申请、专注与刷题记录，并将匹配邮件的本地状态及设置恢复为备份值。已有记录会保留；旧版备份不会修改较新模块。'),t('确认恢复'),{confirmButtonText:t('恢复'),cancelButtonText:t('取消')});if(disposed||requestId!==validationId)return;await axios.post('/api/dashboard/backup/restore',validatedBackup);preview.value=null;backup.value=null;emit('restored');ElMessage.success(t('恢复完成'));await refresh()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(t(e.response?.data?.message||'恢复未完成，请稍后重试'))}finally{busy.restore=false}
}
function cancelBackup(){if(busy.restore)return;validationId++;preview.value=null;backup.value=null;busy.validation=false}
function visible(){if(!document.hidden)refresh()}
onMounted(()=>{refresh();timer=setInterval(visible,15000);document.addEventListener('visibilitychange',visible)})
onBeforeUnmount(()=>{disposed=true;validationId++;clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
</script>
<style scoped>
.operations{max-width:1180px;margin:auto;color:#23324a}.operations header{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:24px}h1{font-size:34px;margin:8px 0}h2{font-size:20px}p{line-height:1.7;color:#73829a;font-size:13px}small{color:#8294ac;font-size:11px}button,.file-button{border:1px solid #dce6f4;background:white;color:#2c65bf;border-radius:11px;padding:10px 14px;font-weight:700;cursor:pointer}button:disabled{opacity:.45;cursor:wait}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}.metrics article,.panel{background:#ffffffcc;border:1px solid white;border-radius:22px;padding:24px;box-shadow:0 10px 30px #1b34540a}.metrics h3{font-size:19px;overflow-wrap:anywhere}.panel{margin-top:22px}.source{display:flex;justify-content:space-between;align-items:center;border-top:1px solid #e9edf4;padding:16px 0;gap:16px}.source p{margin:5px 0}.controls{display:flex;flex-wrap:wrap;gap:12px}.file-button input{display:none}.restore-preview{margin-top:22px;padding:20px;background:#eaf2ff;border-radius:16px}.restore-preview button{margin-right:12px}.notice{background:#fff0d5;padding:14px;border-radius:12px}.footnote{font-size:11px}@media(max-width:900px){.metrics{grid-template-columns:repeat(2,1fr)}}@media(max-width:540px){.metrics{grid-template-columns:1fr}.source{align-items:flex-start}.operations header{flex-wrap:wrap}}
</style>

<template>
  <section class="career-center" :aria-label="t('求职与申请中心')">
    <header class="career-intro">
      <div><span class="eyebrow">YOUR NEXT OPPORTUNITY</span><h2>{{ t("求职与申请中心") }}</h2><p>{{ t("把每一次申请放进清晰的流程里，招聘邮件只给建议，由你决定是否更新。") }}</p></div>
      <div class="header-actions"><button class="analyze" :disabled="analysis.status === 'RUNNING'" @click="startAnalysis">{{ analysis.status === 'RUNNING' ? t("分析中 {v0}/{v1}", { v0: analysis.processed, v1: analysis.total }) : t("分析招聘邮件") }}</button><button class="primary" @click="openEditor()">{{ t("＋ 新增申请") }}</button></div>
    </header>

    <div class="stage-strip" role="tablist" :aria-label="t('申请阶段')">
      <button v-for="stage in stages" :key="stage.key" :class="{ active: filter === stage.key }" @click="filter = stage.key"><span>{{ stage.icon }}</span><b>{{ t(stage.label) }}</b><small>{{ stageCounts[stage.key] || 0 }}</small></button>
      <button :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'"><span>◎</span><b>{{ t("全部") }}</b><small>{{ applications.length }}</small></button>
    </div>

    <p v-if="error" class="notice" role="alert">{{ t(error) }} <button @click="load">{{ t("重试") }}</button></p>
    <section v-if="suggestions.length" class="mail-suggestions" :aria-label="t('招聘邮件建议')">
      <div class="section-heading"><div><span class="eyebrow">AI MAIL SIGNALS</span><h3>{{ t("招聘邮件建议") }}</h3><p>{{ t("确认后才会更新阶段、关联原邮件，并把勾选项加入任务中心。") }}</p></div><span class="suggestion-count">{{ suggestions.length }}</span></div>
      <article v-for="suggestion in suggestions" :key="suggestion.mailId" class="suggestion-card">
        <div class="suggestion-main"><span class="mail-badge">✉</span><div><small>{{ formatDate(suggestion.receivedAt) }} · {{ suggestion.sender }}</small><h4>{{ suggestion.mailSubject }}</h4><p>{{ suggestion.summary }}</p><blockquote>{{ suggestion.evidence }}</blockquote></div></div>
        <div class="suggestion-decision">
          <div class="proposed"><span>{{ t("建议阶段") }}</span><b>{{ stageLabel(suggestion.suggestedStage) }}<template v-if="suggestion.suggestedResult"> · {{ resultLabel(suggestion.suggestedResult) }}</template></b><small>{{ suggestion.company || t("公司待确认") }} · {{ suggestion.role || t("岗位待确认") }}</small></div>
          <label>{{ t("关联到申请") }}<select :value="choices[suggestion.mailId]" @change="chooseApplication(suggestion, $event.target.value)"><option value="">{{ t("请手动选择公司与岗位") }}</option><option v-for="item in applications" :key="item.id" :value="item.id">{{ item.company }} · {{ item.role }} · {{ stageLabel(item.stage) }}</option></select></label>
          <p v-if="!suggestedApplicationId(suggestion)" class="match-hint">{{ t("请手动选择公司与岗位") }}</p>
          <fieldset v-if="suggestion.preparations.length"><legend>{{ t("同时加入准备事项") }}</legend><label v-for="(item,index) in suggestion.preparations" :key="index"><input v-model="preparations[suggestion.mailId]" type="checkbox" :value="index"><span><b>{{ item.title }}</b><small>{{ item.details }}</small></span></label></fieldset>
          <div class="suggestion-actions"><button @click="$emit('open-mail',{ id:suggestion.mailId })">{{ t("查看原邮件") }}</button><button @click="dismiss(suggestion)">{{ t("忽略建议") }}</button><button class="confirm" :disabled="!choices[suggestion.mailId] || saving" @click="apply(suggestion)">{{ t("确认更新并添加事项") }}</button></div>
        </div>
      </article>
    </section>

    <div class="board-heading"><div><span class="eyebrow">APPLICATION PIPELINE</span><h3>{{ filter === 'ALL' ? t("全部申请") : stageLabel(filter) }}</h3></div><span>{{ filtered.length }} {{ t("条记录") }}</span></div>
    <div v-if="loading" class="empty">{{ t("正在同步申请记录…") }}</div>
    <div v-else-if="!filtered.length" class="empty"><span>◇</span><h3>{{ t("这一阶段还没有申请") }}</h3><p>{{ t("新增公司和岗位，或从招聘邮件建议创建记录。") }}</p><button @click="openEditor()">{{ t("记录第一份申请") }}</button></div>
    <div v-else class="application-grid">
      <article v-for="item in filtered" :key="item.id" class="application-card">
        <header><div class="company-avatar">{{ item.company.slice(0,1).toUpperCase() }}</div><div><small>{{ stageLabel(item.stage) }}</small><h3>{{ item.company }}</h3><p>{{ item.role }}</p></div><button :aria-label="t('编辑申请')" @click="openEditor(item)">•••</button></header>
        <div class="application-meta"><span v-if="item.location">⌖ {{ item.location }}</span><span v-if="item.nextActionAt">{{ t("◷ 下一步") }} {{ dateOnly(item.nextActionAt) }}</span><span>✉ {{ item.linkedMails.length }} {{ t("封关联邮件") }}</span></div>
        <p v-if="item.notes" class="notes">{{ item.notes }}</p>
        <div v-if="item.result" :class="['result',item.result.toLowerCase()]">{{ resultLabel(item.result) }}</div>
        <div v-if="item.linkedMails.length" class="linked-mails"><button v-for="mail in item.linkedMails.slice(0,3)" :key="mail.id" @click="$emit('open-mail',{id:mail.id})"><span>✉</span><span><b>{{ mail.subject }}</b><small>{{ formatDate(mail.receivedAt) }}</small></span></button></div>
        <footer><a v-if="item.jobUrl" :href="item.jobUrl" target="_blank" rel="noopener noreferrer">{{ t("岗位页面 ↗") }}</a><button @click="advance(item)">{{ nextStage(item.stage) ? t("推进到") + stageLabel(nextStage(item.stage)) : t("编辑结果") }}</button></footer>
      </article>
    </div>

    <Teleport to="body"><div v-if="editor" class="modal-backdrop" @mousedown.self="closeEditor"><section class="editor" role="dialog" aria-modal="true" aria-labelledby="career-editor-title"><header><div><span class="eyebrow">APPLICATION RECORD</span><h3 id="career-editor-title">{{ editing ? t("编辑申请") : t("新增申请") }}</h3></div><button :aria-label="t('关闭')" @click="closeEditor">×</button></header>
      <form @submit.prevent="save">
        <label>{{ t("公司") }}<input v-model="draft.company" maxlength="200" required :placeholder="t('例如：OpenAI')"></label>
        <label>{{ t("岗位") }}<input v-model="draft.role" maxlength="300" required :placeholder="t('例如：Software Engineer Intern')"></label>
        <label>{{ t("申请阶段") }}<select v-model="draft.stage"><option v-for="stage in stages" :key="stage.key" :value="stage.key">{{ t(stage.label) }}</option></select></label>
        <label v-if="draft.stage === 'RESULT'">{{ t("最终结果") }}<select v-model="draft.result" required><option value="">{{ t("请选择") }}</option><option v-for="item in results" :key="item.key" :value="item.key">{{ t(item.label) }}</option></select></label>
        <label>{{ t("地点") }}<input v-model="draft.location" maxlength="300" :placeholder="t('城市 / Remote（可选）')"></label>
        <label>{{ t("申请日期") }}<input v-model="draft.appliedDate" type="date"></label><label>{{ t("下一步日期") }}<input v-model="draft.nextDate" type="date"></label>
        <label class="wide">{{ t("岗位链接") }}<input v-model="draft.jobUrl" type="url" maxlength="2048" placeholder="https://..."></label>
        <label class="wide">{{ t("备注") }}<textarea v-model="draft.notes" rows="4" maxlength="4000" :placeholder="t('联系人、准备重点、进展说明…')"></textarea></label>
        <section v-if="editing?.linkedMails.length" class="editor-linked-mails wide" :aria-label="t('关联邮件')">
          <h4>{{ t("关联邮件") }} <small>{{ editing.linkedMails.length }}</small></h4>
          <p>{{ t("更改或解除关联只调整邮件归属，不会更改申请阶段或已有准备任务。") }}</p>
          <article v-for="mail in editing.linkedMails" :key="mail.id" class="editor-mail">
            <button type="button" class="editor-mail-subject" @click="$emit('open-mail', { id: mail.id })"><b>{{ mail.subject }}</b><small>{{ formatDate(mail.receivedAt) }}</small></button>
            <div class="mail-link-actions"><button type="button" :disabled="saving || editorStale" @click="openMailLink(mail)">{{ t("更改关联") }}</button><button type="button" :disabled="saving || editorStale" @click="unlinkMail(mail)">{{ t("解除关联") }}</button></div>
            <div v-if="linkMailId === mail.id" class="mail-link-editor">
              <label>{{ t("关联到申请") }}<select v-model="linkTargetId" :disabled="saving || editorStale"><option value="">{{ t("请手动选择公司与岗位") }}</option><option v-for="item in linkTargets" :key="item.id" :value="item.id">{{ item.company }} · {{ item.role }} · {{ stageLabel(item.stage) }}</option></select></label>
              <div class="mail-link-actions"><button type="button" :disabled="saving" @click="closeMailLink">{{ t("取消") }}</button><button type="button" class="primary" :disabled="!linkTargetId || saving || editorStale" @click="moveMail(mail)">{{ t("确认更改关联") }}</button></div>
            </div>
          </article>
        </section>
        <p v-if="linkError" class="modal-error" role="alert">{{ t(linkError) }}</p>
        <p v-if="editorStale" class="modal-error" role="alert">{{ t("申请记录已变化，草稿已保留。请关闭并重新打开申请后重试。") }}</p>
        <p v-if="modalError" class="modal-error" role="alert">{{ t(modalError) }}</p>
        <div class="form-actions wide"><button v-if="editing" type="button" class="delete" :disabled="saving || editorStale" @click="remove">{{ t("删除记录") }}</button><button type="button" @click="closeEditor">{{ t("取消") }}</button><button class="primary" :disabled="saving || editorStale" type="submit">{{ saving ? t("保存中…") : t("保存申请") }}</button></div>
      </form>
    </section></div></Teleport>
  </section>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'

import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
const { t, dateLocale } = useI18n()
defineEmits(['open-mail'])
const stages=[{key:'PREPARING',label:'准备',icon:'◌'},{key:'APPLIED',label:'已投',icon:'↗'},{key:'ASSESSMENT',label:'笔试',icon:'⌨'},{key:'INTERVIEW',label:'面试',icon:'◉'},{key:'RESULT',label:'结果',icon:'✓'}]
const results=[{key:'OFFER',label:'Offer'},{key:'REJECTED',label:'未通过'},{key:'WITHDRAWN',label:'已撤回'},{key:'OTHER',label:'其他结果'}]
const applications=ref([]),suggestions=ref([]),stageCounts=ref({}),analysis=ref({status:'IDLE',total:0,processed:0,failed:0}),loading=ref(true),saving=ref(false),error=ref(''),filter=ref('ALL'),editor=ref(false),editing=ref(null),modalError=ref('')
const explicitChoices=reactive({}),preparations=reactive({}),draft=reactive({company:'',role:'',stage:'PREPARING',result:'',location:'',jobUrl:'',notes:'',appliedDate:'',nextDate:''})
const linkMailId=ref(null),linkTargetId=ref(''),linkError=ref(''),editorStale=ref(false)
let timer,mounted=false,loadSequence=0
const filtered=computed(()=>filter.value==='ALL'?applications.value:applications.value.filter(item=>item.stage===filter.value))
const linkTargets=computed(()=>applications.value.filter(item=>item.id!==editing.value?.id))
const choices=computed(()=>Object.fromEntries(suggestions.value.map(item=>[item.mailId,validExplicitChoice(item)?explicitChoices[item.mailId].applicationId:suggestedApplicationId(item)])))
function applicationIdentity(item){return JSON.stringify([item.company.trim().toLowerCase(),item.role.trim().toLowerCase()])}
function suggestionCompany(item){return (item.company||'').trim().toLowerCase()}
function suggestedApplicationId(item){return applications.value.some(app=>app.id===item.matchedApplicationId)?item.matchedApplicationId:''}
function validExplicitChoice(item){
  const choice=explicitChoices[item.mailId]
  if(!choice||choice.company!==suggestionCompany(item))return false
  if(!choice.applicationId)return true
  const app=applications.value.find(app=>app.id===choice.applicationId)
  return !!app&&choice.identity===applicationIdentity(app)
}
function chooseApplication(item,applicationId){
  const app=applications.value.find(app=>app.id===applicationId)
  explicitChoices[item.mailId]={applicationId:app?.id||'',identity:app?applicationIdentity(app):'',company:suggestionCompany(item)}
}
function stageLabel(key){return t(stages.find(item=>item.key===key)?.label||key)} function resultLabel(key){return t(results.find(item=>item.key===key)?.label||key)}
function nextStage(key){const index=stages.findIndex(item=>item.key===key);return index>=0&&index<stages.length-1?stages[index+1].key:null}
function message(error){return t(error.response?.data?.message||error.message||'请求失败')}
async function load(){
  const sequence=++loadSequence
  try{
    const {data}=await axios.get('/api/job-applications',{timeout:15000})
    if(!mounted||sequence!==loadSequence)return
    applications.value=data.applications||[];suggestions.value=data.suggestions||[];stageCounts.value=data.stageCounts||{};analysis.value=data.analysis||analysis.value;error.value=''
    const activeIds=new Set(suggestions.value.map(item=>String(item.mailId)))
    for(const id of Object.keys(explicitChoices))if(!activeIds.has(id))delete explicitChoices[id]
    for(const id of Object.keys(preparations))if(!activeIds.has(id))delete preparations[id]
    for(const item of suggestions.value){
      if(!validExplicitChoice(item))delete explicitChoices[item.mailId]
      if(!(item.mailId in preparations))preparations[item.mailId]=item.preparations.map((_,i)=>i)
    }
  }catch(e){if(mounted&&sequence===loadSequence)error.value=message(e)}
  finally{if(mounted&&sequence===loadSequence)loading.value=false}
}
async function startAnalysis(){try{analysis.value=(await axios.post('/api/job-applications/analysis',null,{timeout:15000})).data;if(analysis.value.status==='RUNNING')poll()}catch(e){ElMessage.error(message(e))}}
function poll(){clearInterval(timer);timer=setInterval(async()=>{await load();if(analysis.value.status!=='RUNNING'){clearInterval(timer);if(analysis.value.failed)ElMessage.warning(t("{v0} 封邮件暂未完成分析", { v0: analysis.value.failed }))}},1800)}
function openEditor(item=null){editing.value=item;Object.assign(draft,{company:item?.company||'',role:item?.role||'',stage:item?.stage||'PREPARING',result:item?.result||'',location:item?.location||'',jobUrl:item?.jobUrl||'',notes:item?.notes||'',appliedDate:inputDate(item?.appliedAt),nextDate:inputDate(item?.nextActionAt)});modalError.value='';linkError.value='';editorStale.value=false;closeMailLink();editor.value=true}
function closeEditor(){if(!saving.value)editor.value=false} function inputDate(v){return v?new Date(v).toISOString().slice(0,10):''} function dateValue(v){return v?new Date(v+'T12:00:00').getTime():null}
async function save(){if(saving.value||editorStale.value)return;saving.value=true;modalError.value='';const payload={company:draft.company.trim(),role:draft.role.trim(),stage:draft.stage,result:draft.stage==='RESULT'?draft.result:null,location:draft.location.trim(),jobUrl:draft.jobUrl.trim(),notes:draft.notes.trim(),appliedAt:dateValue(draft.appliedDate),nextActionAt:dateValue(draft.nextDate),version:editing.value?.version??null};try{if(editing.value)await axios.put('/api/job-applications/'+editing.value.id,payload,{timeout:15000});else await axios.post('/api/job-applications',payload,{timeout:15000});editor.value=false;ElMessage.success(t("申请记录已保存"));await load()}catch(e){modalError.value=message(e);if(e.response?.status===409){editorStale.value=true;await load()}}finally{saving.value=false}}
async function remove(){if(saving.value||editorStale.value)return;try{await ElMessageBox.confirm(t("删除这条申请记录？已创建的准备任务会保留。"),t("删除申请"),{type:'warning',confirmButtonText:t('确认删除'),cancelButtonText:t('取消')});saving.value=true;await axios.delete('/api/job-applications/'+editing.value.id,{params:{version:editing.value.version}});editor.value=false;ElMessage.success(t("申请记录已删除"));await load()}catch(e){if(e!=='cancel'&&e!=='close')modalError.value=message(e)}finally{saving.value=false}}
function openMailLink(mail){linkMailId.value=mail.id;linkTargetId.value='';linkError.value=''}
function closeMailLink(){linkMailId.value=null;linkTargetId.value=''}
function acceptLinkChange(source,target){
  applications.value=applications.value.map(item=>item.id===source.id?source:item.id===target?.id?target:item)
  editing.value=source
}
async function handleLinkError(e){
  linkError.value=e.response?.data?.message||e.message||'请求失败'
  if(e.response?.status===409){editorStale.value=true;await load()}
}
async function moveMail(mail){
  const target=linkTargets.value.find(item=>item.id===linkTargetId.value)
  if(!target||saving.value||editorStale.value)return
  saving.value=true;linkError.value=''
  try{
    const {data}=await axios.put(`/api/job-applications/${editing.value.id}/mails/${mail.id}`,{targetApplicationId:target.id,applicationVersion:editing.value.version,targetApplicationVersion:target.version},{timeout:15000})
    acceptLinkChange(data.source,data.target);closeMailLink();ElMessage.success(t('邮件关联已更改'));await load()
  }catch(e){await handleLinkError(e)}finally{saving.value=false}
}
async function unlinkMail(mail){
  if(saving.value||editorStale.value)return
  saving.value=true;linkError.value=''
  try{
    await ElMessageBox.confirm(t('解除这封邮件与当前申请的关联？申请阶段和已有准备任务会保留。'),t('解除邮件关联'),{type:'warning',confirmButtonText:t('确认解除'),cancelButtonText:t('取消')})
    const {data}=await axios.delete(`/api/job-applications/${editing.value.id}/mails/${mail.id}`,{params:{version:editing.value.version},timeout:15000})
    acceptLinkChange(data);if(linkMailId.value===mail.id)closeMailLink();ElMessage.success(t('邮件关联已解除'));await load()
  }catch(e){if(e!=='cancel'&&e!=='close')await handleLinkError(e)}finally{saving.value=false}
}
function advance(item){openEditor(item);draft.stage=nextStage(item.stage)||item.stage;draft.result=''}
async function apply(suggestion){const app=applications.value.find(item=>item.id===choices.value[suggestion.mailId]);if(!app||saving.value)return;saving.value=true;try{const {data}=await axios.post(`/api/job-applications/suggestions/${suggestion.mailId}/apply`,{applicationId:app.id,applicationVersion:app.version,suggestionVersion:suggestion.version,preparationIndexes:preparations[suggestion.mailId]||[]},{timeout:20000});ElMessage.success(t("阶段已更新，并新增 {v0} 项准备任务", { v0: data.tasksCreated }));await load()}catch(e){ElMessage.error(message(e));await load()}finally{saving.value=false}}
async function dismiss(suggestion){try{await axios.post(`/api/job-applications/suggestions/${suggestion.mailId}/dismiss`,null,{params:{version:suggestion.version},timeout:15000});await load()}catch(e){ElMessage.error(message(e))}}
function formatDate(v){return v?new Date(v).toLocaleString(dateLocale.value,{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}):t("时间未知")} function dateOnly(v){return new Date(v).toLocaleDateString(dateLocale.value,{month:'numeric',day:'numeric'})}
onMounted(async()=>{mounted=true;await load();await startAnalysis()})
onBeforeUnmount(()=>{mounted=false;clearInterval(timer)})
</script>

<style scoped>
.match-hint{margin:0;color:#97601b;font-size:12px}.editor-linked-mails{min-width:0;margin-top:4px;padding-top:16px;border-top:1px solid #dce5f1}.editor-linked-mails h4{margin:0 0 6px;font-size:16px}.editor-linked-mails h4 small{color:#738198}.editor-linked-mails>p{margin:0 0 14px;color:#64738a;font-size:12px;line-height:1.6}.editor-mail{padding:12px 0;border-top:1px solid #e4eaf3}.editor button{border:0;cursor:pointer;font:inherit}.editor button:disabled{opacity:.5;cursor:not-allowed}.editor-mail-subject{display:grid;gap:4px;width:100%;padding:0;color:#294c80;background:transparent;text-align:left;overflow-wrap:anywhere}.editor-mail-subject small{color:#738198;font-size:11px}.mail-link-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px;margin-top:10px}.mail-link-actions button{padding:8px 11px;border-radius:9px;color:#3563a5;background:#e9f0fa;font-size:12px}.mail-link-actions .primary{color:white;background:#2870df}.mail-link-editor{margin-top:12px;padding:12px;border-radius:12px;background:#edf3fb}
.career-center{max-width:1460px;margin:0 auto;padding:38px 54px 90px;color:#162239}.career-intro,.section-heading,.board-heading{display:flex;justify-content:space-between;align-items:flex-end;gap:24px}.career-intro h2{margin:5px 0 4px;font-size:42px;letter-spacing:-.055em}.career-intro p,.section-heading p{margin:0;color:#718099}.eyebrow{color:#7290bd;font-size:11px;font-weight:900;letter-spacing:.18em}.header-actions{display:flex;gap:10px}.career-center button,.career-center select,.career-center input,.career-center textarea{font:inherit}.career-center button{border:0;cursor:pointer}.primary,.analyze{padding:13px 20px;border-radius:15px;font-weight:800}.primary{color:white;background:linear-gradient(135deg,#2c75ff,#194dc2);box-shadow:0 12px 28px #2968d837}.analyze{color:#3563a5;background:#fff;box-shadow:inset 0 0 0 1px #d7e1ef}.stage-strip{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin:30px 0;padding:8px;border-radius:24px;background:#dfe8f4}.stage-strip button{display:flex;align-items:center;justify-content:center;gap:9px;padding:13px 8px;border-radius:17px;color:#687894;background:transparent}.stage-strip button.active{color:#154fae;background:#fff;box-shadow:0 8px 24px #4f6f9a1c}.stage-strip small{display:grid;place-items:center;min-width:22px;height:22px;border-radius:99px;background:#edf3fb;font-weight:800}.mail-suggestions{margin:0 0 34px;padding:26px;border:1px solid #fff;border-radius:30px;background:linear-gradient(140deg,#f8fbff,#eaf2ff);box-shadow:0 18px 48px #294f7c15}.section-heading h3,.board-heading h3{margin:3px 0 0;font-size:26px}.suggestion-count{display:grid;place-items:center;width:45px;height:45px;border-radius:50%;color:white;background:#3279ec;font-size:20px;font-weight:900}.suggestion-card{display:grid;grid-template-columns:1.2fr .9fr;gap:22px;margin-top:18px;padding:22px;border-radius:24px;background:#fff;box-shadow:0 10px 25px #34527410}.suggestion-main{display:flex;gap:15px}.mail-badge{display:grid;place-items:center;flex:0 0 48px;height:48px;border-radius:15px;color:#fff;background:#397ce8}.suggestion-main small{color:#8090aa}.suggestion-main h4{margin:4px 0 10px;font-size:18px}.suggestion-main p{margin:0;color:#44546e;line-height:1.6}.suggestion-main blockquote{margin:14px 0 0;padding:10px 13px;border-left:3px solid #8db4f3;color:#77869b;background:#f5f8fd;font-size:12px}.suggestion-decision{display:grid;gap:12px;padding-left:22px;border-left:1px solid #e5ebf3}.proposed{display:grid;gap:2px}.proposed span,.suggestion-decision>label{color:#77859b;font-size:11px;font-weight:800}.proposed b{font-size:20px}.proposed small{color:#5c6e87}.suggestion-decision select{width:100%;margin-top:5px;padding:10px;border:1px solid #dce5f1;border-radius:11px;background:white}.suggestion-decision fieldset{display:grid;gap:7px;padding:0;border:0}.suggestion-decision legend{margin-bottom:7px;color:#77859b;font-size:11px;font-weight:800}.suggestion-decision fieldset label{display:flex;align-items:flex-start;gap:8px}.suggestion-decision fieldset span{display:grid}.suggestion-decision fieldset small{color:#738198}.suggestion-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.suggestion-actions button{padding:9px 12px;border-radius:10px;color:#52647e;background:#eef3f9}.suggestion-actions .confirm{color:white;background:#2870df}.board-heading{margin:25px 4px 16px}.board-heading>span{color:#78869b}.application-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}.application-card{display:flex;flex-direction:column;min-height:280px;padding:22px;border:1px solid #fff;border-radius:26px;background:rgba(255,255,255,.87);box-shadow:0 14px 35px #3f536f13}.application-card header{display:flex;gap:13px;align-items:center}.company-avatar{display:grid;place-items:center;width:48px;height:48px;border-radius:15px;color:#fff;background:linear-gradient(145deg,#6d9eff,#2d62ca);font-size:20px;font-weight:900}.application-card header>div:nth-child(2){min-width:0}.application-card header small{color:#3474dd;font-weight:800}.application-card h3,.application-card header p{overflow:hidden;margin:2px 0;text-overflow:ellipsis;white-space:nowrap}.application-card h3{font-size:20px}.application-card header p{color:#66768e}.application-card header>button{margin-left:auto;color:#8190a4;background:transparent}.application-meta{display:flex;flex-wrap:wrap;gap:7px;margin:18px 0}.application-meta span{padding:6px 9px;border-radius:99px;color:#64738a;background:#eff4fa;font-size:11px}.notes{display:-webkit-box;overflow:hidden;margin:0 0 12px;color:#65748a;font-size:12px;line-height:1.55;-webkit-line-clamp:2;-webkit-box-orient:vertical}.result{align-self:flex-start;padding:7px 11px;border-radius:10px;font-weight:800}.result.offer{color:#168a51;background:#e5f8ee}.result.rejected{color:#b84852;background:#fdecef}.linked-mails{display:grid;gap:5px;margin:7px 0}.linked-mails button{display:flex;gap:8px;min-width:0;padding:7px;text-align:left;border-radius:10px;background:#f5f7fb}.linked-mails button>span:last-child{display:grid;min-width:0}.linked-mails b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.linked-mails small{color:#8793a6;font-size:9px}.application-card footer{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:auto;padding-top:16px;border-top:1px solid #edf1f6}.application-card footer a,.application-card footer button{color:#316ac2;font-size:12px;font-weight:800;text-decoration:none;background:transparent}.empty{display:grid;place-items:center;min-height:300px;padding:30px;border:1px solid #fff;border-radius:28px;color:#748299;background:#ffffffa8;text-align:center}.empty>span{font-size:42px}.empty h3{margin:8px}.empty p{margin:0 0 15px}.empty button,.notice button{color:#2869ca;background:transparent;font-weight:800}.notice{padding:13px;border-radius:14px;color:#a4434b;background:#fff0f1}.modal-backdrop{position:fixed;inset:0;z-index:2000;display:grid;place-items:center;padding:20px;background:#15233b99;backdrop-filter:blur(9px)}.editor{width:min(720px,100%);max-height:92vh;overflow:auto;padding:28px;border-radius:28px;background:#f7f9fc;box-shadow:0 35px 90px #101a2d66}.editor>header{display:flex;justify-content:space-between;align-items:flex-start}.editor h3{margin:4px 0 22px;font-size:29px}.editor>header button{font-size:28px;background:transparent}.editor form{display:grid;grid-template-columns:1fr 1fr;gap:15px}.editor label{display:grid;gap:6px;color:#586980;font-size:12px;font-weight:800}.editor input,.editor select,.editor textarea{width:100%;box-sizing:border-box;padding:12px;border:1px solid #d8e1ec;border-radius:12px;background:#fff;color:#18243a}.wide{grid-column:1/-1}.form-actions{display:flex;justify-content:flex-end;gap:9px}.form-actions button{padding:11px 16px;border-radius:11px}.form-actions .delete{margin-right:auto;color:#b8414a;background:#fdebed}.modal-error{grid-column:1/-1;margin:0;color:#b8414a}.career-center button:disabled{opacity:.5;cursor:wait}@media(max-width:1000px){.application-grid{grid-template-columns:repeat(2,1fr)}.suggestion-card{grid-template-columns:1fr}.suggestion-decision{padding:14px 0 0;border-left:0;border-top:1px solid #e5ebf3}}@media(max-width:700px){.career-center{padding:24px 15px 80px}.career-intro{align-items:flex-start;flex-direction:column}.career-intro h2{font-size:32px}.stage-strip{grid-template-columns:repeat(3,1fr)}.application-grid{grid-template-columns:1fr}.editor form{grid-template-columns:1fr}.wide{grid-column:1}.header-actions{width:100%}.header-actions button{flex:1}}
</style>

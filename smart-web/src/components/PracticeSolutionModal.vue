<template>
  <div class="solution-backdrop" @click.self="emit('close')">
    <section class="solution-modal" role="dialog" aria-modal="true" :aria-label="t('{number} 题解笔记', { number: problem.number })">
      <header class="solution-header">
        <div><span class="eyebrow">PERSONAL SOLUTION NOTE</span><h3>{{ problem.number }} · {{ problem.title || `LeetCode #${problem.number}` }}</h3><p>{{ t('这道题的专属题解 · 与练习次数、熟练度和题型通用心得分开保存') }}</p></div>
        <button type="button" class="close-button" :aria-label="t('关闭题解')" @click="emit('close')">×</button>
      </header>
      <div class="solution-body">
        <p v-if="error" class="error" role="alert">{{ t(error) }}</p>
        <p v-if="loading" class="loading">{{ t('正在读取题解…') }}</p>
        <template v-else>
          <div class="editor-toolbar"><strong>{{ t('题解内容') }}</strong><div><button type="button" :class="{ active: !preview }" @click="preview = false">{{ t('编辑') }}</button><button type="button" :class="{ active: preview }" @click="preview = true">{{ t('预览') }}</button></div></div>
          <template v-if="!preview">
            <button v-if="!content.trim()" type="button" class="template-button" @click="insertTemplate">{{ t('插入题解模板') }}</button>
            <textarea v-model="content" maxlength="20000" rows="14" :aria-label="t('单题题解文字')" :placeholder="t('写下题意、解题思路、关键步骤、复杂度和易错点。可以直接粘贴代码；截图可按 Ctrl+V 粘贴到这里。')" @paste="pasteImage"></textarea>
            <small class="word-count">{{ content.length }} {{ t('/ 20000 字') }}</small>
          </template>
          <div v-else class="text-preview">{{ content || t('还没有文字内容。') }}</div>

          <div class="image-title"><div><strong>{{ t('截图') }}</strong><small>{{ t('可粘贴、拖入或选择图片；每题最多 8 张，每张不超过 4 MB') }}</small></div><label class="upload-button">{{ t('添加截图') }}<input type="file" multiple accept="image/png,image/jpeg,image/gif,image/webp" @change="chooseImages" /></label></div>
          <div class="drop-zone" @dragover.prevent @drop.prevent="dropImages">{{ t('将截图拖到这里，或在题解编辑框里按 Ctrl+V') }}</div>
          <div v-if="images.length" class="image-grid">
            <article v-for="(image, index) in images" :key="image.id || image.localId" class="image-card">
              <img :src="image.previewUrl" :alt="image.caption || t('第 {index} 张题解截图', { index: index + 1 })" />
              <input v-model="image.caption" maxlength="200" :aria-label="t('第 {index} 张截图说明', { index: index + 1 })" :placeholder="t('截图说明（选填）')" />
              <div class="image-actions"><button type="button" :disabled="index === 0" @click="swapImage(index, -1)">{{ t('上移') }}</button><button type="button" :disabled="index === images.length - 1" @click="swapImage(index, 1)">{{ t('下移') }}</button><button type="button" class="remove-image" @click="images.splice(index, 1)">{{ t('移除') }}</button></div>
            </article>
          </div>
          <p class="save-help">{{ t('题解会保存在本机数据库，并包含在“个人数据备份”中。清空文字和截图后保存即可删除这份题解。') }}</p>
        </template>
      </div>
      <footer class="solution-footer"><button type="button" @click="emit('close')">{{ t('关闭') }}</button><button type="button" class="save-button" :disabled="loading || saving" @click="save">{{ saving ? t('保存中…') : t('保存题解') }}</button></footer>
    </section>
  </div>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { onMounted, ref } from 'vue'
import axios from 'axios'

const props = defineProps({ problem: { type: Object, required: true } })
const emit = defineEmits(['close', 'saved'])
const content = ref(''), images = ref([]), version = ref(null), loading = ref(true), saving = ref(false), error = ref(''), preview = ref(false)
let nextLocalId = 0
const imageUrl = id => '/api/practice/solution-images/' + encodeURIComponent(id)

async function load() {
  loading.value = true; error.value = ''
  try {
    const { data } = await axios.get(`/api/practice/${props.problem.number}/solution`, { timeout: 12000 })
    content.value = data.content || ''
    images.value = (data.images || []).map(image => ({ ...image, previewUrl: imageUrl(image.id) }))
    version.value = data.version ?? null
  } catch (failure) { error.value = failure.response?.data?.message || '题解读取失败，请重试' }
  finally { loading.value = false }
}
function insertTemplate() {
  content.value = t('题意理解：\n\n核心思路：\n\n关键步骤 / 代码：\n\n时间与空间复杂度：\n\n易错点和复盘：\n')
}
function swapImage(index, offset) {
  const next = [...images.value]; [next[index], next[index + offset]] = [next[index + offset], next[index]]; images.value = next
}
async function appendFiles(files) {
  error.value = ''
  for (const file of files) {
    if (images.value.length >= 8) { error.value = '每道题最多保存 8 张截图'; break }
    if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type) || file.size > 4 * 1024 * 1024 || !file.size) {
      error.value = '只支持不超过 4 MB 的 PNG、JPEG、GIF 或 WebP 图片'; continue
    }
    try {
      const previewUrl = await new Promise((resolve, reject) => {
        const reader = new window.FileReader()
        reader.onload = () => resolve(String(reader.result))
        reader.onerror = () => reject(new Error('读取图片失败'))
        reader.readAsDataURL(file)
      })
      const base64 = previewUrl.split(',')[1]
      if (!base64) throw new Error('图片内容为空')
      images.value.push({ localId: ++nextLocalId, id: null, mimeType: file.type, base64, caption: '', previewUrl })
    } catch { error.value = '截图读取失败，请重新选择图片' }
  }
}
function chooseImages(event) { appendFiles([...event.target.files]); event.target.value = '' }
function dropImages(event) { appendFiles([...event.dataTransfer.files]) }
function pasteImage(event) {
  const files = [...(event.clipboardData?.files || [])].filter(file => file.type.startsWith('image/'))
  if (files.length) { event.preventDefault(); appendFiles(files) }
}
async function save() {
  if (loading.value || saving.value) return
  saving.value = true; error.value = ''
  try {
    const { data } = await axios.put(`/api/practice/${props.problem.number}/solution`, {
      content: content.value,
      version: version.value,
      images: images.value.map(image => ({ id: image.id || null, mimeType: image.id ? null : image.mimeType, base64: image.id ? null : image.base64, caption: image.caption || '' }))
    }, { timeout: 30000 })
    version.value = data.version ?? null
    content.value = data.content || ''
    images.value = (data.images || []).map(image => ({ ...image, previewUrl: imageUrl(image.id) }))
    emit('saved')
  } catch (failure) {
    error.value = failure.response?.data?.message || '题解保存失败，请重试'
    if (failure.response?.status === 409) await load()
  } finally { saving.value = false }
}
onMounted(load)
</script>

<style scoped>
.solution-backdrop{position:fixed;inset:0;z-index:1150;background:#142a4696;display:grid;place-items:center;padding:18px}.solution-modal{width:min(990px,100%);max-height:92vh;display:flex;flex-direction:column;background:#f8fbff;border-radius:24px;box-shadow:0 25px 80px #10243e66;color:#203755}.solution-header{display:flex;justify-content:space-between;gap:18px;padding:26px 30px 20px;background:#20324e;color:white;border-radius:24px 24px 0 0}.solution-header h3{margin:7px 0;font-size:24px}.solution-header p{margin:0;color:#bed0e7;font-size:12px}.eyebrow{font-size:11px;letter-spacing:.15em;color:#b3c7e2;font-weight:850}.close-button{flex:none;border:0;background:#ffffff22;color:white;border-radius:50%;width:36px;height:36px;font-size:24px;cursor:pointer}.solution-body{padding:23px 30px;overflow:auto}.editor-toolbar,.image-title{display:flex;justify-content:space-between;align-items:center;gap:15px;margin-bottom:12px}.editor-toolbar strong,.image-title strong{color:#274260}.editor-toolbar div{display:flex;background:#eaf1fa;padding:4px;border-radius:11px}.editor-toolbar button{border:0;background:transparent;color:#59728e;border-radius:8px;padding:8px 15px;font-weight:750;cursor:pointer}.editor-toolbar button.active{background:white;color:#2c67bc;box-shadow:0 2px 10px #162b4b13}.solution-body textarea{box-sizing:border-box;width:100%;min-height:270px;resize:vertical;border:1px solid #d5e2f2;border-radius:14px;padding:17px;background:white;color:#213750;font-family:inherit;font-size:14px;line-height:1.7}.text-preview{min-height:250px;white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid #d5e2f2;border-radius:14px;padding:17px;background:white;line-height:1.7}.word-count{display:block;text-align:right;margin:5px 0 16px;color:#8295aa}.template-button{border:0;background:#e7f2ff;color:#2c67bc;border-radius:9px;padding:8px 12px;margin-bottom:10px;font-weight:750;cursor:pointer}.image-title{margin-top:19px}.image-title small{display:block;color:#8195ab;font-size:11px;margin-top:4px}.upload-button{position:relative;overflow:hidden;background:#2d6bcc;color:white;border-radius:10px;padding:10px 15px;cursor:pointer;font-size:13px;font-weight:800;white-space:nowrap}.upload-button input{position:absolute;inset:0;opacity:0;cursor:pointer;width:100%}.drop-zone{border:2px dashed #c5d8ef;color:#8298ae;text-align:center;padding:18px;border-radius:13px;background:#f2f7fe;font-size:12px}.image-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px;margin-top:15px}.image-card{min-width:0;padding:10px;border:1px solid #dfebf7;border-radius:14px;background:white}.image-card img{display:block;width:100%;height:220px;object-fit:contain;background:#f3f7fc;border-radius:9px}.image-card input{box-sizing:border-box;width:100%;border:1px solid #d6e3f2;border-radius:9px;padding:10px;margin-top:10px;color:#274260}.image-actions{display:flex;gap:6px;justify-content:flex-end;margin-top:8px}.image-actions button{border:0;background:#edf4fc;color:#4c7097;border-radius:8px;padding:6px 10px;cursor:pointer}.image-actions button:disabled{opacity:.4;cursor:default}.image-actions .remove-image{color:#ba5353;background:#fff0ef}.save-help{color:#8194ab;font-size:11px;line-height:1.6;margin-bottom:0}.solution-footer{display:flex;justify-content:flex-end;gap:10px;padding:17px 30px;border-top:1px solid #e0eaf5}.solution-footer button{border:1px solid #d4e1ef;background:white;color:#3e607e;border-radius:10px;padding:10px 18px;font-weight:750;cursor:pointer}.solution-footer .save-button{background:#2d6bcc;border-color:#2d6bcc;color:white}.solution-footer button:disabled{opacity:.55;cursor:default}.error{padding:10px;border-radius:10px;color:#b54545;background:#fff1ef}.loading{color:#738aa3}.solution-modal :is(button,textarea,input):focus-visible{outline:3px solid #6ca7f6;outline-offset:2px}@media(max-width:650px){.solution-backdrop{padding:5px}.solution-modal{max-height:98vh}.solution-header,.solution-body,.solution-footer{padding-left:17px;padding-right:17px}.image-grid{grid-template-columns:1fr}.image-title{align-items:flex-start;flex-direction:column}}
</style>

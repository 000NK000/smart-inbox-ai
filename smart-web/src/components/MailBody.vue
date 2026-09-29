<template>
  <section class="mail-body-view">
    <div class="body-toolbar">
      <strong>{{ t('邮件正文') }}</strong>
      <div class="body-modes">
        <button type="button" :class="{active: compact}" @click="compact = true">{{ t('主要内容') }}</button>
        <button type="button" :class="{active: !compact}" @click="compact = false">{{ t('完整邮件') }}</button>
      </div>
    </div>
    <small v-if="compact && rendered.hidden">{{ t('已折叠页脚与通用导航，可切换“完整邮件”查看。') }}</small>
    <iframe :srcdoc="rendered.document" sandbox="allow-popups allow-popups-to-escape-sandbox" referrerpolicy="no-referrer" :title="t('邮件正文')" />
  </section>
</template>
<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { computed, ref } from 'vue'
import { renderMailBody } from '../utils/mailBody'
const props = defineProps({ html: String, text: String })
const compact = ref(true)
const rendered = computed(() => renderMailBody(props.html, props.text || t('暂无可显示的正文。'), compact.value))
</script>
<style scoped>
.mail-body-view{margin:0 30px 30px;padding:16px;border:1px solid #e3e8ef;border-radius:18px;background:white}
.body-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
.body-toolbar strong{font-size:14px;color:#355d92}.body-modes{display:flex;gap:4px;border-radius:12px;background:#f0f4f9;padding:4px}
button{border:0;background:transparent;color:#65758c;padding:8px 12px;border-radius:9px;cursor:pointer;font-weight:600}
button.active{background:white;color:#246bde;box-shadow:0 2px 8px #152e5910}small{display:block;color:#8390a2;margin-bottom:10px}
iframe{width:100%;height:540px;border:0;background:white;border-radius:10px}
@media(max-width:640px){.mail-body-view{margin:0 12px 16px;padding:8px}iframe{height:460px}}
</style>

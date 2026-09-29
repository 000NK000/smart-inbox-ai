<template>
  <div class="inbox-chat-container">
    <el-button type="primary" circle size="large" :aria-label="t('询问 Smart Inbox')" class="chat-fab" @click="dialogVisible = true">
      <el-icon><ChatDotRound /></el-icon>
    </el-button>

    <el-dialog
      v-model="dialogVisible"
      :title="t('询问 Smart Inbox')"
      width="400px"
      :before-close="handleClose"
      append-to-body
    >
      <div class="chat-history" ref="chatHistoryRef">
        <div v-for="(msg, index) in messages" :key="index" :class="['message', msg.role]">
          <div class="bubble">
            <template v-if="msg.role === 'ai'">
               <el-icon style="margin-right:5px; vertical-align: middle;"><Service /></el-icon>
               <span v-html="formatMessage(msg.uiKey ? t(msg.uiKey) : msg.content)"></span>
            </template>
            <template v-else>
               {{ msg.content }}
            </template>
          </div>
        </div>
        <div v-if="loading" class="message ai">
          <div class="bubble">{{ t('正在思考…') }}</div>
        </div>
      </div>
      <template #footer>
        <div class="chat-input">
          <el-input
            v-model="inputQuery"
            :placeholder="t('搜索邮件，例如：项目详情…')"
            @keyup.enter="sendMessage"
            :disabled="loading"
          >
             <template #append>
               <el-button @click="sendMessage" :icon="Position" :aria-label="t('发送')" />
             </template>
          </el-input>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { useI18n } from '../i18n/index.js'
const { t } = useI18n()
import { ElButton, ElIcon, ElDialog, ElInput } from 'element-plus'
import { ref, nextTick, watch } from 'vue'
import { ChatDotRound, Service, Position } from '@element-plus/icons-vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const dialogVisible = ref(false)
const inputQuery = ref('')
const loading = ref(false)
const messages = ref([
  { role: 'ai', uiKey: '你好！我可以搜索并概括你的邮件。你想了解什么？' }
])
const chatHistoryRef = ref(null)

const handleClose = (done) => {
  done()
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatHistoryRef.value) {
    chatHistoryRef.value.scrollTop = chatHistoryRef.value.scrollHeight
  }
}

const formatMessage = (text) => {
  // Simple newline to br
  return text.replace(/\n/g, '<br>')
}

const sendMessage = async () => {
  const query = inputQuery.value.trim()
  if (!query) return

  messages.value.push({ role: 'user', content: query })
  inputQuery.value = ''
  loading.value = true
  scrollToBottom()

  try {
    // API Call
    const response = await axios.post('/api/chat', { query })
    const answer = response.data
    messages.value.push({ role: 'ai', content: answer })
  } catch (error) {
    console.error('Chat error:', error)
    messages.value.push({ role: 'ai', uiKey: '搜索邮件时出现问题，请重试。' })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}
</script>

<style scoped>
.chat-fab {
  position: fixed;
  bottom: 30px;
  right: 30px;
  width: 60px;
  height: 60px;
  font-size: 24px;
  box-shadow: 0 4px 10px rgba(0,0,0,0.2);
  z-index: 9999;
}

.chat-history {
  height: 300px;
  overflow-y: auto;
  padding: 10px;
  background-color: #f9f9f9;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.message {
  display: flex;
  width: 100%;
}

.message.user {
  justify-content: flex-end;
}

.message.ai {
  justify-content: flex-start;
}

.bubble {
  max-width: 80%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.4;
}

.message.user .bubble {
  background-color: #409EFF;
  color: #fff;
  border-top-right-radius: 2px;
}

.message.ai .bubble {
  background-color: #e4e7ed;
  color: #303133;
  border-top-left-radius: 2px;
}

.chat-input {
  display: flex;
  gap: 10px;
}
</style>

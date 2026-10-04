<template>
  <MobileAccessShell v-slot="{ mobile, authenticated }">
    <RuntimeWorkspace :mobile="mobile" :paused="!authenticated" />
  </MobileAccessShell>
</template>

<script setup>
import MobileAccessShell from './components/MobileAccessShell.vue'
import RuntimeWorkspace from './RuntimeWorkspace.vue'
</script>

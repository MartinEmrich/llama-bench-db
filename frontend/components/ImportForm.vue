<script setup lang="ts">
import { ref, watch } from 'vue'

const emit = defineEmits<{ (e: 'imported'): void }>()

const computers = ref<any[]>([])
const models = ref<any[]>([])
const versions = ref<any[]>([])
const computerId = ref<number | ''>('')
const versionId = ref<number | ''>('')
const modelId = ref<number | ''>('')
const text = ref('')
const busy = ref(false)
const error = ref('')
const warnings = ref<{ code: string; message: string }[]>([])
const blocked = ref(false)
const success = ref('')

async function loadComputers() { computers.value = await $fetch('/api/computers') }
async function loadModels() { models.value = await $fetch('/api/models') }

watch(computerId, async (id) => {
  versionId.value = ''
  if (!id) { versions.value = []; return }
  versions.value = await $fetch(`/api/computers/${id}/versions`)
})

async function submit(acknowledge: boolean) {
  busy.value = true
  error.value = ''
  warnings.value = []
  blocked.value = false
  success.value = ''
  try {
    const res = await $fetch('/api/results/import', {
      method: 'POST',
      body: {
        computerId: Number(computerId.value),
        versionId: Number(versionId.value),
        modelId: Number(modelId.value),
        text: text.value,
        acknowledgeWarnings: acknowledge
      }
    }) as any
    success.value = `imported ${res.results.length} result${res.results.length === 1 ? '' : 's'}`
    if (res.warnings?.length) warnings.value = res.warnings
    text.value = ''
    emit('imported')
  } catch (e: any) {
    const data = e.data ?? {}
    if (e.status === 409 && data.blocked) {
      blocked.value = true
      warnings.value = data.warnings ?? []
    } else {
      error.value = data.error ?? e.message ?? 'import failed'
    }
  } finally {
    busy.value = false
  }
}

await loadComputers()
await loadModels()
</script>

<template>
  <div class="panel">
    <h2 style="margin-top: 0">Import run results</h2>
    <p class="muted" style="margin-top: -4px">
      Select the computer, its version and the model, then paste the llama-bench console output.
      One model per paste — multiple models are rejected.
    </p>

    <div class="formrow">
      <div class="filter">
        <label>Computer</label>
        <select v-model="computerId">
          <option value="">select…</option>
          <option v-for="c in computers" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div class="filter">
        <label>Version</label>
        <select v-model="versionId" :disabled="!versions.length">
          <option value="">select…</option>
          <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.createdAt.slice(0, 16).replace('T', ' ') }}</option>
        </select>
      </div>
      <div class="filter">
        <label>Model</label>
        <select v-model="modelId">
          <option value="">select…</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }} ({{ m.quantization }})</option>
        </select>
      </div>
    </div>

    <textarea v-model="text" placeholder="| model | size | params | backend | … | test | t/s |&#10;| … paste the console output here …"></textarea>

    <div style="margin-top: 10px; display: flex; gap: 8px">
      <button :disabled="busy || !computerId || !versionId || !modelId || !text.trim()" @click="submit(false)">Import</button>
      <button v-if="blocked" :disabled="busy" @click="submit(true)">Import anyway (acknowledge warnings)</button>
    </div>

    <div v-if="error" class="msg error">{{ error }}</div>
    <div v-for="(w, i) in warnings" :key="i" class="msg warn"><b>{{ w.code }}</b>: {{ w.message }}</div>
    <div v-if="success && !blocked" class="msg ok">{{ success }}</div>
  </div>
</template>

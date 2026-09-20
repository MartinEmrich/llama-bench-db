<script setup lang="ts">
import { ref, watch, computed } from 'vue'

const emit = defineEmits<{ (e: 'imported'): void }>()

const computers = ref<any[]>([])
const models = ref<any[]>([])
const computerId = ref<number | ''>('')
const modelId = ref<number | ''>('')
const text = ref('')
const build = ref('')
const buildDirty = ref(false)
const busy = ref(false)

const pasteHasBuild = computed(() => /^\s*build:\s*\S/m.test(text.value))

// Header-driven extraction of the backend cell from the first table, so the
// build prefill can be scoped to computer + backend like the server sees it.
function extractBackend(t: string): string | null {
  const lines = t.split('\n')
  for (let i = 0; i + 1 < lines.length; i++) {
    const l = lines[i].trim()
    if (!l.startsWith('|')) continue
    const cells = l.replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim())
    if (cells[0] !== 'model') continue
    const idx = cells.indexOf('backend')
    if (idx < 0) return null
    for (let j = i + 2; j < lines.length; j++) {
      const d = lines[j].trim()
      if (!d) continue
      if (!d.startsWith('|')) break
      const dataCells = d.replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim())
      return dataCells[idx] ?? null
    }
    return null
  }
  return null
}

let prefillTimer: ReturnType<typeof setTimeout> | null = null
watch([text, computerId], () => {
  if (prefillTimer) clearTimeout(prefillTimer)
  if (pasteHasBuild.value || !computerId.value) return
  prefillTimer = setTimeout(async () => {
    try {
      const q: Record<string, any> = { computerId: Number(computerId.value) }
      const backend = extractBackend(text.value)
      if (backend) q.backend = backend
      const res = await $fetch('/api/results/latest-build', { query: q }) as any
      if (!buildDirty.value && res?.build) build.value = res.build
    } catch {
      // no known build yet (204) or lookup failed — leave the field alone
    }
  }, 400)
})
const error = ref('')
const warnings = ref<{ code: string; message: string }[]>([])
const blocked = ref(false)
const success = ref('')

async function loadComputers() { computers.value = await $fetch('/api/computers') }
async function loadModels() { models.value = await $fetch('/api/models') }

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
        modelId: Number(modelId.value),
        text: text.value,
        build: build.value.trim(),
        acknowledgeWarnings: acknowledge
      }
    }) as any
    success.value = `imported ${res.results.length} result${res.results.length === 1 ? '' : 's'}`
    if (res.warnings?.length) warnings.value = res.warnings
    text.value = ''
    build.value = ''
    buildDirty.value = false
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
      Select the computer and the model, then paste the llama-bench console output.
      The result is attached to the newest version of the computer.
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
        <label>Model</label>
        <select v-model="modelId">
          <option value="">select…</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }} ({{ m.quantization }})</option>
        </select>
      </div>
    </div>

    <textarea v-model="text" placeholder="| model | size | params | backend | … | test | t/s |&#10;| … paste the console output here …"></textarea>

    <div v-if="!pasteHasBuild" class="formrow" style="margin-top: 10px">
      <div class="filter">
        <label>Build (llama.cpp commit)</label>
        <input v-model="build" placeholder="e.g. 861bd3c10 (11029)" @input="buildDirty = true">
      </div>
    </div>

    <div style="margin-top: 10px; display: flex; gap: 8px">
      <button :disabled="busy || !computerId || !modelId || !text.trim()" @click="submit(false)">Import</button>
      <button v-if="blocked" :disabled="busy" @click="submit(true)">Import anyway (acknowledge warnings)</button>
    </div>

    <div v-if="error" class="msg error">{{ error }}</div>
    <div v-for="(w, i) in warnings" :key="i" class="msg warn"><b>{{ w.code }}</b>: {{ w.message }}</div>
    <div v-if="success && !blocked" class="msg ok">{{ success }}</div>
  </div>
</template>

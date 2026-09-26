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

interface DetectRun { hostname: string | null; hfModelId: string | null }
const detection = ref<{ parseError: string | null; runs: DetectRun[] } | null>(null)

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

// --- autodetection: what the server would resolve per run, previewed live ---
let detectTimer: ReturnType<typeof setTimeout> | null = null
watch(text, () => {
  if (detectTimer) clearTimeout(detectTimer)
  detectTimer = setTimeout(async () => {
    if (!text.value.trim()) { detection.value = null; return }
    try {
      detection.value = await $fetch('/api/results/detect', { method: 'POST', body: { text: text.value } }) as any
    } catch {
      detection.value = null
    }
  }, 400)
})

const runs = computed<DetectRun[]>(() => detection.value?.runs ?? [])
const parseError = computed(() => detection.value?.parseError ?? null)
const detectedHostnames = computed(() => [...new Set(runs.value.map(r => r.hostname).filter(Boolean))])
// a usable -hf id carries a quantization ('uploader/model:QUANT')
const usableHf = (v: string | null) => !!v && v.includes(':')
const detectedHfIds = computed(() => [...new Set(runs.value.map(r => r.hfModelId).filter(usableHf))])

function matchComputer(hostname: string): any | null {
  return computers.value.find(c => c.hostname && c.hostname.toLowerCase() === hostname.toLowerCase()) ?? null
}
const matchedComputers = computed(() => detectedHostnames.value.map(matchComputer))
const unmatchedHostnames = computed(() =>
  detectedHostnames.value.filter((h, i) => !matchedComputers.value[i]))

// Explicit selection wins; autodetect applies while the dropdowns sit at "autodetect".
const computerResolvable = computed(() =>
  runs.value.length > 0 && runs.value.every(r => r.hostname && matchComputer(r.hostname)))
const modelResolvable = computed(() =>
  runs.value.length > 0 && runs.value.every(r => usableHf(r.hfModelId)))
// explicit model + several models in the paste: the server rejects that combination
const multiModelConflict = computed(() => modelId.value !== '' && detectedHfIds.value.length > 1)

const canImport = computed(() =>
  !busy.value &&
  text.value.trim() !== '' &&
  !parseError.value &&
  (computerId.value !== '' || computerResolvable.value) &&
  (modelId.value !== '' || modelResolvable.value) &&
  !multiModelConflict.value)

const computerHint = computed<{ kind: string; text: string } | null>(() => {
  if (computerId.value !== '' || parseError.value || runs.value.length === 0) return null
  if (detectedHostnames.value.length === 0) {
    return { kind: 'muted', text: 'no hostname detected — select a computer' }
  }
  if (unmatchedHostnames.value.length > 0) {
    return { kind: 'warn', text: `hostname not found: ${unmatchedHostnames.value.join(', ')} — select a computer or add it` }
  }
  const names = matchedComputers.value.map(c => c!.name).join(', ')
  return { kind: 'ok', text: `detected: ${names}` }
})

// Shown as a form-level error: an explicit model selection cannot apply to a multi-model paste.
const multiModelError = computed(() => multiModelConflict.value
  ? `paste contains ${detectedHfIds.value.length} models — use autodetect or paste one model at a time`
  : null)

const modelHint = computed<{ kind: string; text: string } | null>(() => {
  if (modelId.value !== '' || parseError.value || runs.value.length === 0) return null
  if (detectedHfIds.value.length === 1) {
    return { kind: 'ok', text: `detected: ${detectedHfIds.value[0]}` }
  }
  if (detectedHfIds.value.length > 1) {
    return { kind: 'ok', text: `${detectedHfIds.value.length} models detected — each run is matched to its model` }
  }
  if (runs.value.some(r => r.hfModelId !== null && !usableHf(r.hfModelId))) {
    return { kind: 'error', text: `-hf without quantization (${runs.value.find(r => r.hfModelId !== null && !usableHf(r.hfModelId))!.hfModelId}) — select a model` }
  }
  if (runs.value.some(r => r.hfModelId === null)) {
    return { kind: 'error', text: 'command line has no -hf parameter — select a model' }
  }
  return { kind: 'muted', text: 'no -hf detected — select a model' }
})

// An explicit model selection that disagrees with the detected -hf pops up a
// warning; declining reverts to autodetect.
function sameHf(a: string, b: string): boolean {
  const i = a.lastIndexOf(':'); const j = b.lastIndexOf(':')
  const ra = i > 0 ? a.slice(0, i) : a; const qa = i > 0 ? a.slice(i + 1) : ''
  const rb = j > 0 ? b.slice(0, j) : b; const qb = j > 0 ? b.slice(j + 1) : ''
  return ra === rb && qa.toLowerCase() === qb.toLowerCase()
}

function onModelChange() {
  if (modelId.value === '' || detectedHfIds.value.length !== 1) return
  const m = models.value.find(x => x.id === modelId.value)
  if (!m) return
  if (sameHf(detectedHfIds.value[0], `${m.modelId}:${m.quantization}`)) return
  const keep = confirm(
    `The paste was run with -hf ${detectedHfIds.value[0]}\n` +
    `but you selected ${m.name} (${m.modelId}:${m.quantization}).\n\nImport with the selected model?`)
  if (!keep) modelId.value = ''
}

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
        computerId: computerId.value === '' ? null : Number(computerId.value),
        modelId: modelId.value === '' ? null : Number(modelId.value),
        text: text.value,
        build: build.value.trim(),
        acknowledgeWarnings: acknowledge
      }
    }) as any
    const pairs = [...new Set(res.results.map((r: any) => `${r.computerName} / ${r.modelId}`))]
    success.value = `imported ${res.results.length} result${res.results.length === 1 ? '' : 's'}` +
      (pairs.length > 1 ? `: ${pairs.join(', ')}` : '')
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
      Paste the llama-bench console output — the computer (from the shell prompt) and the model
      (from the -hf parameter) are autodetected per run. Choose a computer or model explicitly to
      override; the result is attached to the newest version of the computer.
    </p>

    <div class="formrow">
      <div class="filter">
        <label>Computer</label>
        <select v-model="computerId">
          <option value="">autodetect</option>
          <option v-for="c in computers" :key="c.id" :value="c.id">{{ c.name }}<span v-if="c.hostname" class="muted"> ({{ c.hostname }})</span></option>
        </select>
        <div v-if="computerHint" :class="['hint', computerHint.kind]">{{ computerHint.text }}</div>
      </div>
      <div class="filter">
        <label>Model</label>
        <select v-model="modelId" @change="onModelChange">
          <option value="">autodetect</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.modelId }} ({{ m.quantization }})</option>
        </select>
        <div v-if="modelHint" :class="['hint', modelHint.kind]">{{ modelHint.text }}</div>
      </div>
    </div>

    <textarea v-model="text" placeholder="| model | size | params | backend | … | test | t/s |&#10;| … paste the console output here …"></textarea>

    <div v-if="parseError" class="msg warn">paste not parseable: {{ parseError }}</div>
    <div v-if="multiModelError" class="msg error">{{ multiModelError }}</div>

    <div v-if="!pasteHasBuild" class="formrow" style="margin-top: 10px">
      <div class="filter">
        <label>Build (llama.cpp commit)</label>
        <input v-model="build" placeholder="e.g. 861bd3c10 (11029)" @input="buildDirty = true">
      </div>
    </div>

    <div style="margin-top: 10px; display: flex; gap: 8px">
      <button :disabled="!canImport" @click="submit(false)">Import</button>
      <button v-if="blocked" :disabled="busy" @click="submit(true)">Import anyway (acknowledge warnings)</button>
    </div>

    <div v-if="error" class="msg error">{{ error }}</div>
    <div v-for="(w, i) in warnings" :key="i" class="msg warn"><b>{{ w.code }}</b>: {{ w.message }}</div>
    <div v-if="success && !blocked" class="msg ok">{{ success }}</div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'

interface ResultRow {
  id: number
  importedAt: string
  computerId: number
  versionId: number
  computerName: string
  versionDate: string
  modelRef: number
  modelName: string
  modelId: string
  quantization: string
  modelString: string
  sizeGiB: number | null
  backend: string | null
  devices: string | null
  ngl: number
  typeK: string
  typeV: string
  fa: boolean
  threads: number | null
  ts: string | null
  loadMode: string
  ppTokens: number
  tgTokens: number
  ppTps: number
  tgTps: number
  ppDeviation: number
  tgDeviation: number
  build: string | null
  params: Record<string, unknown>
}

const computers = ref<any[]>([])
const models = ref<any[]>([])
const pageData = ref<{ content: ResultRow[]; page: number; size: number; totalElements: number; totalPages: number }>({
  content: [], page: 0, size: 25, totalElements: 0, totalPages: 0
})

// Sentinel filter value for "devices is null" (runs whose devices are unknown).
const EMPTY = '__empty__'

const filters = ref({
  computerId: '', model: '', quant: '', device: '',
  ppMin: '', ppMax: '', tgMin: '', tgMax: '',
  ppTpsMin: '', ppTpsMax: '', tgTpsMin: '', tgTpsMax: ''
})
const sort = ref('importedAt,desc')
const page = ref(0)

async function loadComputers() { computers.value = await $fetch('/api/computers') }
async function loadModels() { models.value = await $fetch('/api/models') }

const deviceValues = ref<string[]>([])
async function loadDeviceValues() {
  const q: Record<string, any> = {}
  if (filters.value.computerId !== '') q.computerId = filters.value.computerId
  deviceValues.value = await $fetch('/api/results/device-values', { query: q })
}

async function loadResults() {
  const q: Record<string, any> = { sort: sort.value, page: page.value, size: 25 }
  for (const [k, v] of Object.entries(filters.value)) {
    if (v === '') continue
    if (k === 'device') {
      if (v === EMPTY) q.devicesEmpty = true
      else q.devices = v
      continue
    }
    q[k] = v
  }
  pageData.value = await $fetch('/api/results', { query: q })
}

function onSort(field: string) {
  const [f, dir] = sort.value.split(',')
  sort.value = f === field ? `${field},${dir === 'asc' ? 'desc' : 'asc'}` : `${field},desc`
  loadResults()
}

function sortIndicator(field: string): string {
  const [f, dir] = sort.value.split(',')
  return f === field ? (dir === 'asc' ? ' ▲' : ' ▼') : ''
}

// Unknown llama-bench parameters, keys sorted so rows with the same param
// set line up column-wise in the table.
function sortedParams(r: ResultRow): [string, string][] {
  return Object.entries(r.params ?? {})
    .map(([k, v]) => [k, String(v)])
    .sort(([a], [b]) => a.localeCompare(b))
}

// The Build column only earns its width when the current page actually
// distinguishes builds; otherwise the value is still reachable via hover.
const showBuildColumn = computed(() => new Set(pageData.value.content.map(r => r.build)).size > 1)

const details = ref<{ row: ResultRow; top: number; left: number } | null>(null)
let hoverTimer: ReturnType<typeof setTimeout> | null = null

function onRowEnter(e: MouseEvent, r: ResultRow) {
  if (hoverTimer) clearTimeout(hoverTimer)
  const el = e.currentTarget as HTMLElement
  hoverTimer = setTimeout(() => {
    const rect = el.getBoundingClientRect()
    const estHeight = 34 + (3 + sortedParams(r).length) * 19
    const top = rect.bottom + estHeight > window.innerHeight
      ? Math.max(8, rect.top - estHeight - 4)
      : rect.bottom + 4
    details.value = { row: r, top, left: Math.min(rect.left + 16, window.innerWidth - 280) }
  }, 1000)
}

function onRowLeave() {
  if (hoverTimer) clearTimeout(hoverTimer)
  hoverTimer = null
  details.value = null
}

async function removeResult(r: ResultRow) {
  if (!confirm(`Delete result ${r.modelName} (${r.computerName}, ${r.importedAt.slice(0, 10)})?`)) return
  try {
    await $fetch(`/api/results/${r.id}`, { method: 'DELETE' })
    loadResults()
  } catch (e: any) {
    alert(e.data?.error ?? e.message)
  }
}

// Edit dialog. The form always sends every field: null = unchanged, empty
// string = cleared (backend convention), so prefilled values round-trip as-is.
const editing = ref<ResultRow | null>(null)
const editBusy = ref(false)
const editError = ref('')
const form = ref<Record<string, any>>({})
const versions = ref<any[]>([])
const paramRows = ref<{ key: string; value: string }[]>([])

async function loadVersions(computerId: number) {
  const detail = await $fetch(`/api/computers/${computerId}`)
  versions.value = detail.versions ?? []
}

function openEdit(r: ResultRow) {
  editing.value = r
  editError.value = ''
  form.value = {
    computerId: r.computerId, versionId: r.versionId, modelRef: r.modelRef,
    importedAt: r.importedAt,
    modelString: r.modelString ?? '', sizeGiB: r.sizeGiB ?? '',
    backend: r.backend ?? '', devices: r.devices ?? '',
    ngl: r.ngl, typeK: r.typeK, typeV: r.typeV, fa: r.fa,
    threads: r.threads ?? '', ts: r.ts ?? '', loadMode: r.loadMode, build: r.build ?? ''
  }
  paramRows.value = Object.entries(r.params ?? {}).map(([key, value]) => ({ key, value: String(value) }))
  loadVersions(r.computerId)
}

// Switching the computer invalidates the selected version; default to the newest.
async function onEditComputerChange() {
  form.value.versionId = null
  await loadVersions(form.value.computerId)
  if (form.value.versionId === null && versions.value.length > 0) {
    form.value.versionId = versions.value[0].id
  }
}

function addParamRow() { paramRows.value.push({ key: '', value: '' }) }
function removeParamRow(i: number) { paramRows.value.splice(i, 1) }

function paramsFromRows(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const r of paramRows.value) {
    const k = r.key.trim()
    if (k) out[k] = r.value
  }
  return out
}

async function saveEdit() {
  editBusy.value = true
  editError.value = ''
  try {
    await $fetch(`/api/results/${editing.value.id}`, {
      method: 'PUT',
      body: {
        computerId: form.value.computerId,
        versionId: form.value.versionId,
        modelId: form.value.modelRef,
        importedAt: form.value.importedAt || null,
        modelString: form.value.modelString,
        sizeGiBObserved: form.value.sizeGiB,
        backend: form.value.backend,
        devices: form.value.devices,
        ngl: form.value.ngl === null || form.value.ngl === undefined ? '' : String(form.value.ngl),
        typeK: form.value.typeK,
        typeV: form.value.typeV,
        fa: form.value.fa,
        threads: form.value.threads,
        ts: form.value.ts,
        loadMode: form.value.loadMode,
        build: form.value.build,
        params: paramsFromRows()
      }
    })
    editing.value = null
    await Promise.all([loadResults(), loadDeviceValues()])
  } catch (e: any) {
    editError.value = e.data?.error ?? e.message
  } finally {
    editBusy.value = false
  }
}

// One entry per base model (modelId), regardless of how many quants exist.
const baseModels = computed(() => {
  const seen = new Map<string, any>()
  for (const m of models.value) if (!seen.has(m.modelId)) seen.set(m.modelId, m)
  return [...seen.values()]
})

// Quants available for the selected base model; all quants when none is selected.
const quantOptions = computed(() => {
  const sel = filters.value.model
  return [...new Set(models.value.filter((m: any) => !sel || m.modelId === sel).map((m: any) => m.quantization))]
})

// Drop a quant that does not exist for the newly selected base model.
watch(() => filters.value.model, () => {
  if (filters.value.quant !== '' && !quantOptions.value.includes(filters.value.quant)) {
    filters.value.quant = ''
  }
})

watch(filters, () => { page.value = 0; loadResults() }, { deep: true })

// The devices dropdown is scoped to the selected computer.
watch(() => filters.value.computerId, async () => {
  await loadDeviceValues()
  if (filters.value.device !== '' && filters.value.device !== EMPTY
      && !deviceValues.value.includes(filters.value.device)) {
    filters.value.device = ''
  }
})

function onImported() { loadResults(); loadModels(); loadDeviceValues() }

await Promise.all([loadComputers(), loadModels(), loadDeviceValues(), loadResults()])
</script>

<template>
  <div>
    <h1>Results</h1>

    <ClientOnly>
      <ImportForm @imported="onImported" />
    </ClientOnly>

    <div class="panel">
      <div class="filters">
        <div class="filter">
          <label>Computer</label>
          <select v-model="filters.computerId">
            <option value="">all</option>
            <option v-for="c in computers" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="filter">
          <label>Model</label>
          <select v-model="filters.model">
            <option value="">all</option>
            <option v-for="m in baseModels" :key="m.modelId" :value="m.modelId">{{ m.name }}</option>
          </select>
        </div>
        <div class="filter">
          <label>Quant</label>
          <select v-model="filters.quant">
            <option value="">all</option>
            <option v-for="q in quantOptions" :key="q" :value="q">{{ q }}</option>
          </select>
        </div>
        <div class="filter">
          <label>Devices</label>
          <select v-model="filters.device">
            <option value="">all</option>
            <option v-for="c in deviceValues" :key="c" :value="c">{{ c }}</option>
            <option :value="EMPTY">(empty)</option>
          </select>
        </div>
        <div class="filter"><label>PP tok min</label><input type="number" v-model="filters.ppMin"></div>
        <div class="filter"><label>PP tok max</label><input type="number" v-model="filters.ppMax"></div>
        <div class="filter"><label>TG tok min</label><input type="number" v-model="filters.tgMin"></div>
        <div class="filter"><label>TG tok max</label><input type="number" v-model="filters.tgMax"></div>
        <div class="filter"><label>PP t/s min</label><input type="number" step="0.1" v-model="filters.ppTpsMin"></div>
        <div class="filter"><label>PP t/s max</label><input type="number" step="0.1" v-model="filters.ppTpsMax"></div>
        <div class="filter"><label>TG t/s min</label><input type="number" step="0.1" v-model="filters.tgTpsMin"></div>
        <div class="filter"><label>TG t/s max</label><input type="number" step="0.1" v-model="filters.tgTpsMax"></div>
      </div>

      <table>
        <thead>
          <tr>
            <th class="sortable" @click="onSort('importedAt')">Imported{{ sortIndicator('importedAt') }}</th>
            <th class="sortable" @click="onSort('computer')">Computer{{ sortIndicator('computer') }}</th>
            <th class="sortable" @click="onSort('model')">Model{{ sortIndicator('model') }}</th>
            <th class="sortable" @click="onSort('quant')">Quant{{ sortIndicator('quant') }}</th>
            <th>Backend</th>
            <th>Devices</th>
            <th class="num sortable" @click="onSort('ngl')">ngl{{ sortIndicator('ngl') }}</th>
            <th>KV Cache</th>
            <th>Params</th>
            <th v-if="showBuildColumn">Build</th>
            <th class="num sortable" @click="onSort('ppTokens')">PP tok{{ sortIndicator('ppTokens') }}</th>
            <th class="num sortable" @click="onSort('tgTokens')">TG tok{{ sortIndicator('tgTokens') }}</th>
            <th class="num sortable" @click="onSort('ppTps')">PP t/s{{ sortIndicator('ppTps') }}</th>
            <th class="num sortable" @click="onSort('tgTps')">TG t/s{{ sortIndicator('tgTps') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in pageData.content" :key="r.id" @mouseenter="onRowEnter($event, r)" @mouseleave="onRowLeave">
            <td class="muted">{{ r.importedAt.slice(0, 16).replace('T', ' ') }}</td>
            <td>{{ r.computerName }} <span class="muted">{{ r.versionDate.slice(0, 10) }}</span></td>
            <td :title="r.modelId">{{ r.modelName }}</td>
            <td>{{ r.quantization }}</td>
            <td>{{ r.backend ?? '' }}</td>
            <td>{{ r.devices ?? '' }}</td>
            <td class="num">{{ r.ngl }}</td>
            <td>{{ r.typeK }}/{{ r.typeV }}<span v-if="!r.fa" class="muted" style="font-size:12px"> (No FA)</span></td>
            <td class="params-cell">
              <span v-for="[k, v] in sortedParams(r)" :key="k" class="param" :title="`${k}=${v}`">{{ k }}=<span class="param-val">{{ v }}</span></span>
            </td>
            <td v-if="showBuildColumn">{{ r.build ?? '' }}</td>
            <td class="num">{{ r.ppTokens }}</td>
            <td class="num">{{ r.tgTokens }}</td>
            <td class="num">{{ r.ppTps.toFixed(2) }}</td>
            <td class="num">{{ r.tgTps.toFixed(2) }}</td>
            <td>
              <div class="actions">
                <button class="secondary small" title="Edit result" @click="openEdit(r)">✎</button>
                <button class="danger small" title="Delete result" @click="removeResult(r)">✕</button>
              </div>
            </td>
          </tr>
          <tr v-if="pageData.content.length === 0">
            <td :colspan="showBuildColumn ? 15 : 14" class="muted">no results</td>
          </tr>
        </tbody>
      </table>

      <div v-if="details" class="details-box" :style="{ top: details.top + 'px', left: details.left + 'px' }">
        <div class="details-row"><span class="k">build</span><span>{{ details.row.build ?? '—' }}</span></div>
        <div class="details-row"><span class="k">pp deviation</span><span>± {{ details.row.ppDeviation }}</span></div>
        <div class="details-row"><span class="k">tg deviation</span><span>± {{ details.row.tgDeviation }}</span></div>
        <div v-for="[k, v] in sortedParams(details.row)" :key="k" class="details-row"><span class="k">{{ k }}</span><span>{{ v }}</span></div>
      </div>

      <div class="pager">
        <button class="secondary small" :disabled="page === 0" @click="page--; loadResults()">← prev</button>
        <span class="muted">page {{ pageData.page + 1 }} / {{ Math.max(pageData.totalPages, 1) }} ({{ pageData.totalElements }} results)</span>
        <button class="secondary small" :disabled="pageData.page + 1 >= pageData.totalPages" @click="page++; loadResults()">next →</button>
      </div>
    </div>

    <div v-if="editing" class="modal-backdrop" @click.self="editing = null">
      <div class="modal">
        <h2>Edit result</h2>
        <p class="muted" style="margin-top: -4px">
          {{ editing.modelName }} ({{ editing.quantization }}) on {{ editing.computerName }} — emptying a text field clears its value.
        </p>

        <h3>Attribution</h3>
        <div class="formrow">
          <div class="filter"><label>Computer</label>
            <select v-model="form.computerId" @change="onEditComputerChange">
              <option v-for="c in computers" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="filter"><label>Version</label>
            <select v-model="form.versionId">
              <option v-for="v in versions" :key="v.id" :value="v.id">
                {{ (v.createdAt ?? '').slice(0, 19).replace('T', ' ') }}{{ v.description ? ' — ' + v.description : '' }}
              </option>
            </select>
          </div>
          <div class="filter"><label>Model</label>
            <select v-model="form.modelRef">
              <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }} ({{ m.quantization }})</option>
            </select>
          </div>
        </div>

        <h3>Run</h3>
        <div class="formrow">
          <div class="filter"><label>Backend</label><input v-model="form.backend"></div>
          <div class="filter"><label>Devices (used)</label>
            <input v-model="form.devices" list="edit-device-options">
          </div>
          <datalist id="edit-device-options">
            <option v-for="c in deviceValues" :key="c" :value="c"></option>
          </datalist>
          <div class="filter"><label>ngl</label><input type="number" v-model="form.ngl"></div>
          <div class="filter"><label>Type K</label><input v-model="form.typeK"></div>
          <div class="filter"><label>Type V</label><input v-model="form.typeV"></div>
          <div class="filter"><label>FA</label>
            <select v-model="form.fa">
              <option :value="true">yes</option>
              <option :value="false">no</option>
            </select>
          </div>
          <div class="filter"><label>Threads</label><input type="number" v-model="form.threads"></div>
          <div class="filter"><label>Load mode</label><input v-model="form.loadMode"></div>
        </div>
        <div class="formrow">
          <div class="filter wide"><label>Model string</label><input v-model="form.modelString"></div>
          <div class="filter"><label>Size GiB</label><input type="number" step="0.1" v-model="form.sizeGiB"></div>
          <div class="filter"><label>Build</label><input v-model="form.build"></div>
          <div class="filter"><label>ts</label><input v-model="form.ts"></div>
          <div class="filter wide"><label>Imported at (ISO 8601)</label><input v-model="form.importedAt"></div>
        </div>

        <h3>Params</h3>
        <table>
          <thead>
            <tr><th style="width: 200px">Key</th><th>Value</th><th style="width: 40px"></th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in paramRows" :key="i">
              <td><input v-model="row.key"></td>
              <td><input v-model="row.value"></td>
              <td><button class="danger small" title="Remove" @click="removeParamRow(i)">✕</button></td>
            </tr>
          </tbody>
        </table>
        <div class="formrow">
          <button class="secondary small" @click="addParamRow">+ add param</button>
        </div>

        <div v-if="editError" class="msg error">{{ editError }}</div>

        <div class="formrow" style="justify-content: flex-end">
          <button class="secondary" :disabled="editBusy" @click="editing = null">Cancel</button>
          <button :disabled="editBusy" @click="saveEdit">Save</button>
        </div>
      </div>
    </div>
  </div>
</template>

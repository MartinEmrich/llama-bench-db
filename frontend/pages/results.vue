<script setup lang="ts">
import { ref, watch, computed } from 'vue'

interface ResultRow {
  id: number
  importedAt: string
  computerName: string
  versionDate: string
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

const filters = ref({
  computerId: '', modelId: '', quant: '',
  ppMin: '', ppMax: '', tgMin: '', tgMax: '',
  ppTpsMin: '', ppTpsMax: '', tgTpsMin: '', tgTpsMax: ''
})
const sort = ref('importedAt,desc')
const page = ref(0)

async function loadComputers() { computers.value = await $fetch('/api/computers') }
async function loadModels() { models.value = await $fetch('/api/models') }

async function loadResults() {
  const q: Record<string, any> = { sort: sort.value, page: page.value, size: 25 }
  for (const [k, v] of Object.entries(filters.value)) if (v !== '') q[k] = v
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

watch(filters, () => { page.value = 0; loadResults() }, { deep: true })

function onImported() { loadResults(); loadModels() }

await Promise.all([loadComputers(), loadModels(), loadResults()])
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
          <select v-model="filters.modelId">
            <option value="">all</option>
            <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }} ({{ m.quantization }})</option>
          </select>
        </div>
        <div class="filter">
          <label>Quant</label>
          <select v-model="filters.quant">
            <option value="">all</option>
            <option v-for="m in [...new Set(models.map((m: any) => m.quantization))]" :key="m" :value="m">{{ m }}</option>
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
            <th class="num">fa</th>
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
            <td class="num">{{ r.fa ? 1 : 0 }}</td>
            <td class="params-cell">
              <span v-for="[k, v] in sortedParams(r)" :key="k" class="param" :title="`${k}=${v}`">{{ k }}=<span class="param-val">{{ v }}</span></span>
            </td>
            <td v-if="showBuildColumn">{{ r.build ?? '' }}</td>
            <td class="num">{{ r.ppTokens }}</td>
            <td class="num">{{ r.tgTokens }}</td>
            <td class="num">{{ r.ppTps.toFixed(2) }}</td>
            <td class="num">{{ r.tgTps.toFixed(2) }}</td>
            <td><button class="danger small" title="Delete result" @click="removeResult(r)">✕</button></td>
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
  </div>
</template>

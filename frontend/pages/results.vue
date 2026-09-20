<script setup lang="ts">
import { ref, watch } from 'vue'

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
            <th class="num sortable" @click="onSort('ppTokens')">PP tok{{ sortIndicator('ppTokens') }}</th>
            <th class="num sortable" @click="onSort('tgTokens')">TG tok{{ sortIndicator('tgTokens') }}</th>
            <th class="num sortable" @click="onSort('ppTps')">PP t/s{{ sortIndicator('ppTps') }}</th>
            <th class="num sortable" @click="onSort('tgTps')">TG t/s{{ sortIndicator('tgTps') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in pageData.content" :key="r.id">
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
            <td class="num">{{ r.ppTokens }}</td>
            <td class="num">{{ r.tgTokens }}</td>
            <td class="num" :title="`± ${r.ppDeviation}`">{{ r.ppTps.toFixed(2) }}</td>
            <td class="num" :title="`± ${r.tgDeviation}`">{{ r.tgTps.toFixed(2) }}</td>
          </tr>
          <tr v-if="pageData.content.length === 0">
            <td colspan="13" class="muted">no results</td>
          </tr>
        </tbody>
      </table>

      <div class="pager">
        <button class="secondary small" :disabled="page === 0" @click="page--; loadResults()">← prev</button>
        <span class="muted">page {{ pageData.page + 1 }} / {{ Math.max(pageData.totalPages, 1) }} ({{ pageData.totalElements }} results)</span>
        <button class="secondary small" :disabled="pageData.page + 1 >= pageData.totalPages" @click="page++; loadResults()">next →</button>
      </div>
    </div>
  </div>
</template>

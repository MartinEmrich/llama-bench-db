<script setup lang="ts">
import { ref } from 'vue'

const route = useRoute()
const id = Number(route.params.id)

interface DeviceRow { key: string; value: string }

const detail = ref<any>(null)
const newName = ref('')
const newHostname = ref('')
const error = ref('')
const busy = ref(false)

// Version & hardware form: by default it edits the newest version; checking
// "Create new version?" turns it into a creator. Toggling never touches the
// entry fields, so entered values survive the switch.
const createNew = ref(false)
const editVersionId = ref<number | null>(null)
const desc = ref('')
const deviceRows = ref<DeviceRow[]>([{ key: 'CPU', value: '' }, { key: 'MEM', value: '' }])

function emptyRows(): DeviceRow[] {
  return [{ key: 'CPU', value: '' }, { key: 'MEM', value: '' }]
}

function rowsFrom(devices: Record<string, string> | null): DeviceRow[] {
  const rows = [{ key: 'CPU', value: devices?.CPU ?? '' }, { key: 'MEM', value: devices?.MEM ?? '' }]
  for (const [k, v] of Object.entries(devices ?? {})) {
    if (k !== 'CPU' && k !== 'MEM') rows.push({ key: k, value: v })
  }
  return rows
}

function devicesFromRows(): Record<string, string> {
  const out: Record<string, string> = {}
  for (const r of deviceRows.value) {
    const k = r.key.trim()
    const v = r.value.trim()
    if (k && v) out[k] = v
  }
  return out
}

// Prefills the form from the newest version (initial load, after saving).
function resetForm() {
  const latest = detail.value?.versions?.[0]
  if (latest) {
    prefillFrom(latest)
  } else {
    editVersionId.value = null
    desc.value = ''
    deviceRows.value = emptyRows()
  }
}

function prefillFrom(v: any) {
  editVersionId.value = v.id
  desc.value = v.description ?? ''
  deviceRows.value = rowsFrom(v.devices)
}

function selectedVersion() {
  return detail.value?.versions?.find((x: any) => x.id === editVersionId.value) ?? null
}

function prefillFromSelected() {
  const v = selectedVersion()
  if (v) prefillFrom(v)
}

async function load() {
  try {
    detail.value = await $fetch(`/api/computers/${id}`)
    newName.value = detail.value.name
    newHostname.value = detail.value.hostname ?? ''
  } catch (e: any) {
    error.value = `failed to load computer ${id}: ${e.data?.error ?? e.message}`
  }
}

async function rename() {
  busy.value = true
  error.value = ''
  try {
    await $fetch(`/api/computers/${id}`, { method: 'PUT', body: { name: newName.value, hostname: newHostname.value } })
    await load()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

async function saveVersion() {
  busy.value = true
  error.value = ''
  try {
    const body = { description: desc.value, devices: devicesFromRows() }
    if (createNew.value) {
      await $fetch(`/api/computers/${id}/versions`, { method: 'POST', body })
    } else {
      await $fetch(`/api/computers/${id}/versions/${editVersionId.value}`, { method: 'PUT', body })
    }
    await load()
    resetForm()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

function addRow() { deviceRows.value.push({ key: '', value: '' }) }
function removeRow(i: number) { deviceRows.value.splice(i, 1) }

async function remove() {
  if (!confirm(`Delete computer '${detail.value.name}'? Only possible when it has no results.`)) return
  try {
    await $fetch(`/api/computers/${id}`, { method: 'DELETE' })
    navigateTo('/computers')
  } catch (e: any) {
    alert(e.data?.error ?? e.message)
  }
}

await load()
resetForm()
</script>

<template>
  <div v-if="!detail && !error" class="muted">loading…</div>
  <div v-else-if="error && !detail" class="msg error">{{ error }}</div>
  <div v-else>
    <h1>Computer: {{ detail.name }}</h1>

    <div class="panel">
      <h2 style="margin-top: 0">Rename</h2>
      <p class="muted" style="margin-top: -4px">
        The hostname is matched against the shell prompt of pasted console output to autodetect this computer on import.
      </p>
      <div class="formrow">
        <div class="filter"><label>Name</label><input v-model="newName"></div>
        <div class="filter"><label>Hostname</label><input v-model="newHostname" placeholder="e.g. martinssurfacego"></div>
        <button :disabled="busy || !newName.trim() || (newName === detail.name && newHostname === (detail.hostname ?? ''))" @click="rename">Save</button>
      </div>
    </div>

    <div class="panel">
      <h2 style="margin-top: 0">Version &amp; hardware</h2>
      <p class="muted" style="margin-top: -4px">
        Create a new version when hardware or setup changed, or edit an existing one to augment/correct its hardware.
        Device names from benchmark runs (e.g. Vulkan0, OPENVINO0_NPU) are matched against these entries on import;
        CPU and MEM are free text.
      </p>
      <div class="formrow">
        <label style="display: flex; gap: 6px; align-items: center; padding-bottom: 8px">
          <input type="checkbox" v-model="createNew"> Create new version?
        </label>
        <select v-if="!createNew" v-model="editVersionId" @change="prefillFromSelected()">
          <option v-for="v in detail.versions" :key="v.id" :value="v.id">
            {{ (v.createdAt ?? '').slice(0, 19).replace('T', ' ') }}{{ v.description ? ' — ' + v.description : '' }}
          </option>
        </select>
      </div>
      <div class="formrow">
        <div class="filter wide"><label>Description</label><input v-model="desc"></div>
      </div>
      <table>
        <thead>
          <tr><th style="width: 180px">Device</th><th>Description</th><th style="width: 40px"></th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in deviceRows" :key="i">
            <td><input v-model="row.key" :disabled="row.key === 'CPU' || row.key === 'MEM'"></td>
            <td><input v-model="row.value"></td>
            <td v-if="!(row.key === 'CPU' || row.key === 'MEM')">
              <button class="danger small" title="Remove" @click="removeRow(i)">✕</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="formrow">
        <button class="secondary small" @click="addRow">+ add device</button>
        <button :disabled="busy || (!createNew && editVersionId === null)" @click="saveVersion">
          {{ createNew ? 'Create version' : 'Save version' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="msg error">{{ error }}</div>

    <table>
      <thead>
        <tr><th>Version (timestamp)</th><th>Description</th><th>Hardware</th></tr>
      </thead>
      <tbody>
        <tr v-for="v in detail.versions" :key="v.id">
          <td>{{ v.createdAt ? v.createdAt.slice(0, 19).replace('T', ' ') : '' }}</td>
          <td>{{ v.description ?? '' }}</td>
          <td>
            <span v-for="(val, k) in v.devices ?? {}" :key="k" class="param" :title="`${k}: ${val}`">{{ k }}: {{ val }}</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div style="margin-top: 16px">
      <button class="danger" @click="remove">Delete computer</button>
    </div>
  </div>
</template>

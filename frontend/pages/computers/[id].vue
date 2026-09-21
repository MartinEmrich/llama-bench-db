<script setup lang="ts">
import { ref } from 'vue'

const route = useRoute()
const id = Number(route.params.id)

const detail = ref<any>(null)
const newName = ref('')
const newHostname = ref('')
const newVersionDesc = ref('')
const error = ref('')
const busy = ref(false)

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

async function addVersion() {
  busy.value = true
  error.value = ''
  try {
    await $fetch(`/api/computers/${id}/versions`, { method: 'POST', body: { description: newVersionDesc.value } })
    newVersionDesc.value = ''
    await load()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

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
      <h2 style="margin-top: 0">Add version</h2>
      <p class="muted" style="margin-top: -4px">
        Add a new version when hardware or setup changed. Results always keep the version they were imported with.
      </p>
      <div class="formrow">
        <div class="filter wide"><label>Description</label><input v-model="newVersionDesc" placeholder="e.g. upgraded GPU to RX 7900 XTX"></div>
        <button :disabled="busy" @click="addVersion">Add version</button>
      </div>
    </div>

    <div v-if="error" class="msg error">{{ error }}</div>

    <table>
      <thead>
        <tr><th>Version (timestamp)</th><th>Description</th></tr>
      </thead>
      <tbody>
        <tr v-for="v in detail.versions" :key="v.id">
          <td>{{ v.createdAt ? v.createdAt.slice(0, 19).replace('T', ' ') : '' }}</td>
          <td>{{ v.description ?? '' }}</td>
        </tr>
      </tbody>
    </table>

    <div style="margin-top: 16px">
      <button class="danger" @click="remove">Delete computer</button>
    </div>
  </div>
</template>

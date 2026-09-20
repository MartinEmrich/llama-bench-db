<script setup lang="ts">
import { ref } from 'vue'

const computers = ref<any[]>([])
const newName = ref('')
const newDescription = ref('')
const busy = ref(false)
const error = ref('')

async function load() { computers.value = await $fetch('/api/computers') }

async function create() {
  busy.value = true
  error.value = ''
  try {
    await $fetch('/api/computers', { method: 'POST', body: { name: newName.value, description: newDescription.value } })
    newName.value = ''
    newDescription.value = ''
    await load()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

async function remove(c: any) {
  if (!confirm(`Delete computer '${c.name}'? Only possible when it has no results.`)) return
  try {
    await $fetch(`/api/computers/${c.id}`, { method: 'DELETE' })
    await load()
  } catch (e: any) {
    alert(e.data?.error ?? e.message)
  }
}

await load()
</script>

<template>
  <div>
    <h1>Computers</h1>

    <div class="panel">
      <h2 style="margin-top: 0">Add computer</h2>
      <div class="formrow">
        <div class="filter"><label>Name</label><input v-model="newName" placeholder=""></div>
        <div class="filter wide"><label>Description (first version)</label><input v-model="newDescription" placeholder="hardware, OS, drivers…"></div>
        <button :disabled="busy || !newName.trim()" @click="create">Add</button>
      </div>
      <div v-if="error" class="msg error">{{ error }}</div>
    </div>

    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th class="num">Versions</th>
          <th>Latest version</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in computers" :key="c.id">
          <td><NuxtLink :to="`/computers/${c.id}`">{{ c.name }}</NuxtLink></td>
          <td class="num">{{ c.versionCount }}</td>
          <td class="muted">{{ c.latestVersionAt ? c.latestVersionAt.slice(0, 16).replace('T', ' ') : '—' }}</td>
          <td><button class="danger small" @click="remove(c)">delete</button></td>
        </tr>
        <tr v-if="!computers.length"><td colspan="4" class="muted">no computers yet</td></tr>
      </tbody>
    </table>
  </div>
</template>

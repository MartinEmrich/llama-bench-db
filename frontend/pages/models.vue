<script setup lang="ts">
import { ref } from 'vue'

const models = ref<any[]>([])
const newHfId = ref('')
const newSize = ref('')
const busy = ref(false)
const error = ref('')
const editing = ref<any>(null)
const editForm = ref({ name: '', modelId: '', quantization: '', sizeGiB: '' })

async function load() { models.value = await $fetch('/api/models') }

// client-side preview of how the server will parse a full HF id
const preview = computed(() => {
  const id = newHfId.value.trim()
  if (!id) return null
  const colon = id.lastIndexOf(':')
  if (colon <= 0 || colon === id.length - 1) return null
  const repo = id.slice(0, colon)
  const quant = id.slice(colon + 1)
  let base = repo.includes('/') ? repo.slice(repo.indexOf('/') + 1) : repo
  if (base.toUpperCase().endsWith('-GGUF')) base = base.slice(0, -5)
  return { name: base, modelId: repo, quantization: quant }
})

async function create() {
  busy.value = true
  error.value = ''
  try {
    const body: any = { modelId: newHfId.value }
    if (newSize.value !== '') body.sizeGiB = Number(newSize.value)
    await $fetch('/api/models', { method: 'POST', body })
    newHfId.value = ''
    newSize.value = ''
    await load()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

function startEdit(m: any) {
  editing.value = m
  editForm.value = { name: m.name, modelId: m.modelId, quantization: m.quantization, sizeGiB: m.sizeGiB ?? '' }
}

async function saveEdit() {
  busy.value = true
  error.value = ''
  try {
    const body: any = { ...editForm.value }
    if (body.sizeGiB === '') delete body.sizeGiB
    else body.sizeGiB = Number(body.sizeGiB)
    await $fetch(`/api/models/${editing.value.id}`, { method: 'PUT', body })
    editing.value = null
    await load()
  } catch (e: any) {
    error.value = e.data?.error ?? e.message
  } finally {
    busy.value = false
  }
}

async function remove(m: any) {
  if (!confirm(`Delete model '${m.name} (${m.quantization})'? Only possible when it has no results.`)) return
  try {
    await $fetch(`/api/models/${m.id}`, { method: 'DELETE' })
    await load()
  } catch (e: any) {
    alert(e.data?.error ?? e.message)
  }
}

await load()
</script>

<template>
  <div>
    <h1>Models</h1>

    <div class="panel">
      <h2 style="margin-top: 0">Add model</h2>
      <p class="muted" style="margin-top: -4px">Paste a full Hugging Face id, e.g. <code>unsloth/Qwen3.5-4B-GGUF:Q4_K_M</code></p>
      <div class="formrow">
        <div class="filter wide"><label>HF model id</label><input v-model="newHfId" placeholder="uploader/name-GGUF:QUANT"></div>
        <div class="filter"><label>Size (GiB, optional)</label><input type="number" step="0.01" v-model="newSize"></div>
        <button :disabled="busy || !preview" @click="create">Add</button>
      </div>
      <div v-if="preview" class="muted">
        → name: {{ preview.name }} · modelId: {{ preview.modelId }} · quant: {{ preview.quantization }}
      </div>
      <div v-if="error" class="msg error">{{ error }}</div>
    </div>

    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th>Model ID</th>
          <th>Quant</th>
          <th class="num">Size (GiB)</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <template v-for="m in models" :key="m.id">
          <tr v-if="editing?.id !== m.id">
            <td>{{ m.name }}</td>
            <td class="muted">{{ m.modelId }}</td>
            <td>{{ m.quantization }}</td>
            <td class="num">{{ m.sizeGiB ?? '—' }}</td>
            <td class="actions">
              <button class="secondary small" @click="startEdit(m)">edit</button>
              <button class="danger small" @click="remove(m)">delete</button>
            </td>
          </tr>
          <tr v-else>
            <td><input v-model="editForm.name"></td>
            <td><input v-model="editForm.modelId"></td>
            <td><input v-model="editForm.quantization"></td>
            <td><input type="number" step="0.01" v-model="editForm.sizeGiB"></td>
            <td class="actions">
              <button class="small" :disabled="busy" @click="saveEdit">save</button>
              <button class="secondary small" @click="editing = null">cancel</button>
            </td>
          </tr>
        </template>
        <tr v-if="!models.length"><td colspan="5" class="muted">no models yet</td></tr>
      </tbody>
    </table>
  </div>
</template>

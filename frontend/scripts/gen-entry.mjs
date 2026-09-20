// Generates dist/ (index.html + _nuxt/) from the Nuxt 4 build output.
// Nuxt 4 with ssr:false does not emit a static index.html; the built nitro
// server renders the SPA entry on demand, so we fetch it once and persist it.
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const port = 39871

const server = spawn(process.execPath, [path.join(root, '.output/server/index.mjs')], {
  env: { ...process.env, PORT: String(port) },
  stdio: 'ignore'
})

let html = null
for (let i = 0; i < 100 && !html; i++) {
  await new Promise(r => setTimeout(r, 200))
  try {
    const res = await fetch(`http://127.0.0.1:${port}/`)
    if (res.ok) html = await res.text()
  } catch {
    // server not up yet
  }
}

server.kill()

if (!html || !html.includes('id="__nuxt"')) {
  console.error('gen-entry: failed to capture SPA entry HTML')
  process.exit(1)
}

const dist = path.join(root, 'dist')
fs.rmSync(dist, { recursive: true, force: true })
fs.mkdirSync(path.join(dist, '_nuxt'), { recursive: true })
fs.cpSync(path.join(root, '.output/public/_nuxt'), path.join(dist, '_nuxt'), { recursive: true })
fs.writeFileSync(path.join(dist, 'index.html'), html)
console.log('gen-entry: dist/ written (index.html + _nuxt/)')

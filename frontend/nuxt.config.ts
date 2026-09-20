export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  ssr: false,
  vite: {
    server: {
      proxy: {
        // Nuxt 4's devProxy (nitro) does not apply to the Vite SPA fallback,
        // so the API proxy has to be configured on the Vite dev server.
        '/api': 'http://localhost:8080'
      }
    }
  },
  css: ['~/assets/main.css'],
  app: {
    head: {
      title: 'llama-bench-db',
      meta: [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }]
    }
  }
})

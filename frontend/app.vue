<script setup lang="ts">
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <NuxtLink to="/results" class="brand">llama-bench-db</NuxtLink>
      <nav>
        <NuxtLink to="/results">Results</NuxtLink>
        <NuxtLink to="/computers">Computers</NuxtLink>
        <NuxtLink to="/models">Models</NuxtLink>
      </nav>
    </header>
    <main class="content">
      <NuxtPage />
    </main>
  </div>
</template>

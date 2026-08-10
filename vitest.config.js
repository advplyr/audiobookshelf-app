import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Unit tests for the WebView (Nuxt) JS layer.
// These run under Node and mock the Capacitor native bridges so the auth
// refresh logic can be exercised without a device/emulator.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/unit/**/*.spec.js']
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./', import.meta.url))
    }
  }
})

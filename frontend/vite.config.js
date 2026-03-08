import { defineConfig } from 'vite'
import { resolve } from 'node:path'

export default defineConfig({
  base: './',
  define: {
    global: 'globalThis',
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    cssCodeSplit: false,
    lib: {
      entry: resolve(__dirname, 'src/main.js'),
      name: 'AMLLBundle',
      formats: ['iife'],
      fileName: () => 'amll.bundle.js',
    },
  },
  server: {
    port: 5173,
  },
})

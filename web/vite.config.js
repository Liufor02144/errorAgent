import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue2';
import { resolve } from 'path';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/inspect': 'http://localhost:8080'
    }
  },
  build: {
    outDir: resolve(__dirname, '../src/main/resources/static'),
    emptyOutDir: true
  }
});

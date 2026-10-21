import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: [{ find: /^@\//, replacement: '/src/' }],
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          mui: ['@mui/material', '@emotion/react', '@emotion/styled'],
          charts: ['@mui/x-charts'],
        },
      },
    },
  },
  // 개발 서버: /api는 로컬 API(api/ bootRun, :8080)로 넘긴다. 브라우저에는 같은 출처라 로그인 쿠키가 그대로 붙는다
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
});

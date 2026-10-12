import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5174,
    strictPort: true,
    open: '/?variant=A',
    proxy: {
      '/api': {
        target: process.env.GEOBOOK_PROTOTYPE_BACKEND ?? 'http://192.168.68.70:8080',
        changeOrigin: true,
        // The prototype can read the collection but cannot change the live server.
        bypass(req, res) {
          if (req.method !== 'GET' && req.method !== 'HEAD') {
            res?.writeHead(405);
            res?.end('The layout prototype is read-only.');
            return false;
          }
        },
      },
      '/notices': process.env.GEOBOOK_PROTOTYPE_BACKEND ?? 'http://192.168.68.70:8080',
    },
  },
});

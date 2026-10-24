'use strict';
const CACHE_VERSION = '1.4.0';
const CACHE = 'koalabel-' + CACHE_VERSION;
const CORE = [
  './',
  'index.html',
  'manifest.json',
  'icon-192.png',
  'icon-512.png',
  'icon-maskable-512.png',
  'lib/pdf.min.js',
  'lib/pdf.worker.min.js',
  'lib/pdf-lib.min.js'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k.startsWith('koalabel-') && k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', e => { if (e.data === 'skipWaiting') self.skipWaiting(); });

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Coquille (page, index.html, manifest) : réseau d'abord sans cache HTTP, cache si hors ligne
  const shell = req.mode === 'navigate' || url.origin === location.origin &&
    (/\/(index\.html|manifest\.json)$/.test(url.pathname) || url.pathname === new URL('./', location).pathname);
  if (shell) {
    e.respondWith(
      fetch(req.url, { cache: 'reload', credentials: 'same-origin' }).then(r => {
        // seule la page de l'appli est mise en cache (pas une autre page du dépôt)
        if (r.ok && (url.pathname.endsWith('/index.html') || url.pathname === new URL('./', location).pathname)) {
          const copy = r.clone();
          caches.open(CACHE).then(c => c.put('index.html', copy));
        }
        return r;
      }).catch(() => caches.match(req, { ignoreSearch: true }).then(hit => hit || caches.match('index.html')))
    );
    return;
  }

  // Polices Google : cache d'abord, complété à la volée
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(
      caches.match(req).then(hit => hit || fetch(req).then(r => {
        const copy = r.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
        return r;
      }).catch(() => new Response('', { status: 504 })))
    );
    return;
  }

  // Fichiers de l'appli (bibliothèques, icônes) : cache d'abord
  if (url.origin === location.origin) {
    e.respondWith(caches.match(req, { ignoreSearch: true }).then(hit => hit || fetch(req)));
  }
});

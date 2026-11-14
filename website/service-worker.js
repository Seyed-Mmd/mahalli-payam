const CACHE='mahalli-shell-v1';
const CORE=['./offline.html','./assets/style.css','./assets/site.js','./assets/icon.svg','./assets/icon-192.png','./assets/icon-512.png','./manifest.webmanifest'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(CORE)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  const request=event.request;
  if(request.method!=='GET') return;
  const url=new URL(request.url);
  if(url.origin!==self.location.origin) return;
  if(request.mode==='navigate') {
    event.respondWith(fetch(request).catch(async()=>await caches.match('./offline.html')));
    return;
  }
  if(url.pathname.endsWith('.php')||url.pathname.includes('/api.php')) return;
  event.respondWith(caches.match(request).then(cached=>cached||fetch(request).then(response=>{
    if(response.ok) { const copy=response.clone(); caches.open(CACHE).then(cache=>cache.put(request,copy)); }
    return response;
  })));
});

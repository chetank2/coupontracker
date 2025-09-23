// Service Worker for Coupon Trainer Mobile PWA
const CACHE_NAME = 'coupon-trainer-v1.0.0';
const STATIC_CACHE = 'coupon-trainer-static-v1';
const DYNAMIC_CACHE = 'coupon-trainer-dynamic-v1';

// Files to cache for offline functionality
const STATIC_FILES = [
  '/',
  '/index.html',
  '/upload.html', 
  '/data.html',
  '/css/style.css',
  '/css/mobile.css',
  '/js/main.js',
  '/js/upload.js',
  '/js/annotation.js',
  '/js/storage.js',
  '/js/training.js',
  '/manifest.json'
];

// Install event - cache static files
self.addEventListener('install', event => {
  console.log('[SW] Installing...');
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then(cache => {
        console.log('[SW] Caching static files');
        return cache.addAll(STATIC_FILES);
      })
      .then(() => {
        console.log('[SW] Static files cached');
        return self.skipWaiting();
      })
      .catch(err => {
        console.error('[SW] Failed to cache static files:', err);
      })
  );
});

// Activate event - clean up old caches
self.addEventListener('activate', event => {
  console.log('[SW] Activating...');
  event.waitUntil(
    caches.keys()
      .then(cacheNames => {
        return Promise.all(
          cacheNames
            .filter(cacheName => {
              return cacheName.startsWith('coupon-trainer-') && 
                     cacheName !== STATIC_CACHE && 
                     cacheName !== DYNAMIC_CACHE;
            })
            .map(cacheName => {
              console.log('[SW] Deleting old cache:', cacheName);
              return caches.delete(cacheName);
            })
        );
      })
      .then(() => {
        console.log('[SW] Activated');
        return self.clients.claim();
      })
  );
});

// Fetch event - serve from cache, fallback to network
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip non-GET requests
  if (request.method !== 'GET') {
    return;
  }

  // Skip external requests
  if (!url.origin.includes(self.location.origin)) {
    return;
  }

  event.respondWith(
    caches.match(request)
      .then(cachedResponse => {
        if (cachedResponse) {
          console.log('[SW] Serving from cache:', request.url);
          return cachedResponse;
        }

        // Not in cache, fetch from network
        return fetch(request)
          .then(networkResponse => {
            // Don't cache non-successful responses
            if (!networkResponse || networkResponse.status !== 200) {
              return networkResponse;
            }

            // Clone the response (can only be consumed once)
            const responseClone = networkResponse.clone();

            // Cache dynamic content
            caches.open(DYNAMIC_CACHE)
              .then(cache => {
                console.log('[SW] Caching dynamic content:', request.url);
                cache.put(request, responseClone);
              });

            return networkResponse;
          })
          .catch(error => {
            console.error('[SW] Network request failed:', error);
            
            // Return offline page for navigation requests
            if (request.destination === 'document') {
              return caches.match('/offline.html') || 
                     caches.match('/index.html');
            }
            
            throw error;
          });
      })
  );
});

// Background sync for uploading annotations when online
self.addEventListener('sync', event => {
  console.log('[SW] Background sync:', event.tag);
  
  if (event.tag === 'upload-annotations') {
    event.waitUntil(
      uploadPendingAnnotations()
    );
  }
});

// Handle push notifications (future feature)
self.addEventListener('push', event => {
  console.log('[SW] Push notification received');
  
  const options = {
    body: event.data ? event.data.text() : 'New training update available',
    icon: '/icons/icon-192x192.png',
    badge: '/icons/icon-72x72.png',
    tag: 'coupon-trainer-notification',
    requireInteraction: false,
    actions: [
      {
        action: 'view',
        title: 'View App'
      },
      {
        action: 'dismiss', 
        title: 'Dismiss'
      }
    ]
  };

  event.waitUntil(
    self.registration.showNotification('Coupon Trainer', options)
  );
});

// Helper function to upload pending annotations
async function uploadPendingAnnotations() {
  try {
    // Get pending annotations from IndexedDB
    const pendingData = await getPendingAnnotations();
    
    if (pendingData.length === 0) {
      console.log('[SW] No pending annotations to upload');
      return;
    }

    // Upload each annotation
    for (const annotation of pendingData) {
      try {
        const response = await fetch('/api/annotations', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(annotation)
        });

        if (response.ok) {
          console.log('[SW] Uploaded annotation:', annotation.id);
          await removePendingAnnotation(annotation.id);
        }
      } catch (error) {
        console.error('[SW] Failed to upload annotation:', error);
      }
    }
  } catch (error) {
    console.error('[SW] Background sync failed:', error);
  }
}

// Helper functions for IndexedDB operations (simplified)
async function getPendingAnnotations() {
  // This would connect to IndexedDB and get pending items
  // For now, return empty array
  return [];
}

async function removePendingAnnotation(id) {
  // This would remove the uploaded annotation from IndexedDB
  console.log('[SW] Removing uploaded annotation:', id);
}

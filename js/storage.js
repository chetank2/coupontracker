// Local Storage Management for Coupon Trainer PWA
// Handles offline data storage using IndexedDB

class CouponStorage {
    constructor() {
        this.dbName = 'CouponTrainerDB';
        this.dbVersion = 1;
        this.db = null;
        this.isInitialized = false;
    }

    // Initialize IndexedDB
    async init() {
        if (this.isInitialized) return true;

        return new Promise((resolve, reject) => {
            const request = indexedDB.open(this.dbName, this.dbVersion);

            request.onerror = () => {
                console.error('Failed to open database:', request.error);
                reject(request.error);
            };

            request.onsuccess = () => {
                this.db = request.result;
                this.isInitialized = true;
                console.log('Database initialized successfully');
                resolve(true);
            };

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                console.log('Upgrading database...');

                // Create coupons store
                if (!db.objectStoreNames.contains('coupons')) {
                    const couponStore = db.createObjectStore('coupons', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    couponStore.createIndex('filename', 'filename', { unique: false });
                    couponStore.createIndex('uploadDate', 'uploadDate', { unique: false });
                    couponStore.createIndex('status', 'status', { unique: false });
                }

                // Create annotations store
                if (!db.objectStoreNames.contains('annotations')) {
                    const annotationStore = db.createObjectStore('annotations', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    annotationStore.createIndex('couponId', 'couponId', { unique: false });
                    annotationStore.createIndex('fieldType', 'fieldType', { unique: false });
                    annotationStore.createIndex('createdAt', 'createdAt', { unique: false });
                }

                // Create training sessions store
                if (!db.objectStoreNames.contains('trainingSessions')) {
                    const sessionStore = db.createObjectStore('trainingSessions', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    sessionStore.createIndex('startTime', 'startTime', { unique: false });
                    sessionStore.createIndex('status', 'status', { unique: false });
                }

                // Create app settings store
                if (!db.objectStoreNames.contains('settings')) {
                    const settingsStore = db.createObjectStore('settings', { 
                        keyPath: 'key' 
                    });
                }
            };
        });
    }

    // Save coupon image data
    async saveCoupon(imageData, filename) {
        await this.init();

        const couponData = {
            filename: filename,
            imageData: imageData, // Base64 encoded image
            uploadDate: new Date().toISOString(),
            status: 'uploaded',
            annotations: [],
            metadata: {
                size: imageData.length,
                type: 'image'
            }
        };

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readwrite');
            const store = transaction.objectStore('coupons');
            const request = store.add(couponData);

            request.onsuccess = () => {
                console.log('Coupon saved:', filename);
                resolve(request.result); // Returns the generated ID
            };

            request.onerror = () => {
                console.error('Failed to save coupon:', request.error);
                reject(request.error);
            };
        });
    }

    // Get all coupons
    async getAllCoupons() {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readonly');
            const store = transaction.objectStore('coupons');
            const request = store.getAll();

            request.onsuccess = () => {
                resolve(request.result);
            };

            request.onerror = () => {
                console.error('Failed to get coupons:', request.error);
                reject(request.error);
            };
        });
    }

    // Get coupon by ID
    async getCoupon(id) {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readonly');
            const store = transaction.objectStore('coupons');
            const request = store.get(id);

            request.onsuccess = () => {
                resolve(request.result);
            };

            request.onerror = () => {
                console.error('Failed to get coupon:', request.error);
                reject(request.error);
            };
        });
    }

    // Update coupon status
    async updateCouponStatus(id, status) {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readwrite');
            const store = transaction.objectStore('coupons');
            
            store.get(id).onsuccess = (event) => {
                const coupon = event.target.result;
                if (coupon) {
                    coupon.status = status;
                    coupon.updatedAt = new Date().toISOString();
                    
                    const updateRequest = store.put(coupon);
                    updateRequest.onsuccess = () => resolve(true);
                    updateRequest.onerror = () => reject(updateRequest.error);
                } else {
                    reject(new Error('Coupon not found'));
                }
            };
        });
    }

    // Save annotation
    async saveAnnotation(couponId, annotation) {
        await this.init();

        const annotationData = {
            couponId: couponId,
            fieldType: annotation.fieldType,
            boundingBox: annotation.boundingBox,
            confidence: annotation.confidence || 1.0,
            createdAt: new Date().toISOString(),
            status: 'pending_upload'
        };

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['annotations'], 'readwrite');
            const store = transaction.objectStore('annotations');
            const request = store.add(annotationData);

            request.onsuccess = () => {
                console.log('Annotation saved for coupon:', couponId);
                resolve(request.result);
            };

            request.onerror = () => {
                console.error('Failed to save annotation:', request.error);
                reject(request.error);
            };
        });
    }

    // Get annotations for a coupon
    async getAnnotations(couponId) {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['annotations'], 'readonly');
            const store = transaction.objectStore('annotations');
            const index = store.index('couponId');
            const request = index.getAll(couponId);

            request.onsuccess = () => {
                resolve(request.result);
            };

            request.onerror = () => {
                console.error('Failed to get annotations:', request.error);
                reject(request.error);
            };
        });
    }

    // Delete coupon and its annotations
    async deleteCoupon(id) {
        await this.init();

        return new Promise(async (resolve, reject) => {
            try {
                const transaction = this.db.transaction(['coupons', 'annotations'], 'readwrite');
                
                // Delete coupon
                const couponStore = transaction.objectStore('coupons');
                couponStore.delete(id);
                
                // Delete associated annotations
                const annotationStore = transaction.objectStore('annotations');
                const index = annotationStore.index('couponId');
                const annotations = await new Promise((res, rej) => {
                    const req = index.getAll(id);
                    req.onsuccess = () => res(req.result);
                    req.onerror = () => rej(req.error);
                });
                
                annotations.forEach(annotation => {
                    annotationStore.delete(annotation.id);
                });

                transaction.oncomplete = () => {
                    console.log('Coupon and annotations deleted:', id);
                    resolve(true);
                };

                transaction.onerror = () => {
                    reject(transaction.error);
                };
            } catch (error) {
                reject(error);
            }
        });
    }

    // Get storage statistics
    async getStorageStats() {
        await this.init();

        try {
            const coupons = await this.getAllCoupons();
            const totalCoupons = coupons.length;
            const annotatedCoupons = coupons.filter(c => c.status === 'annotated').length;
            const pendingUpload = coupons.filter(c => c.status === 'pending_upload').length;
            
            // Calculate storage usage
            let totalSize = 0;
            coupons.forEach(coupon => {
                if (coupon.imageData) {
                    totalSize += coupon.imageData.length;
                }
            });
            
            // Get quota information
            let storageQuota = 0;
            let storageUsed = 0;
            
            if ('storage' in navigator && 'estimate' in navigator.storage) {
                const estimate = await navigator.storage.estimate();
                storageQuota = estimate.quota || 0;
                storageUsed = estimate.usage || 0;
            }

            return {
                totalCoupons,
                annotatedCoupons,
                pendingUpload,
                totalSize,
                storageUsed,
                storageQuota,
                storageUsedMB: Math.round(storageUsed / (1024 * 1024)),
                storageQuotaMB: Math.round(storageQuota / (1024 * 1024))
            };
        } catch (error) {
            console.error('Failed to get storage stats:', error);
            return {
                totalCoupons: 0,
                annotatedCoupons: 0,
                pendingUpload: 0,
                totalSize: 0,
                storageUsed: 0,
                storageQuota: 0,
                storageUsedMB: 0,
                storageQuotaMB: 0
            };
        }
    }

    // Export data for backup/sync
    async exportData() {
        await this.init();

        try {
            const coupons = await this.getAllCoupons();
            const allAnnotations = [];

            // Get all annotations
            for (const coupon of coupons) {
                const annotations = await this.getAnnotations(coupon.id);
                allAnnotations.push(...annotations);
            }

            const exportData = {
                version: '1.0.0',
                exportDate: new Date().toISOString(),
                coupons: coupons,
                annotations: allAnnotations
            };

            return exportData;
        } catch (error) {
            console.error('Failed to export data:', error);
            throw error;
        }
    }

    // Import data from backup
    async importData(importData) {
        await this.init();

        try {
            const transaction = this.db.transaction(['coupons', 'annotations'], 'readwrite');
            const couponStore = transaction.objectStore('coupons');
            const annotationStore = transaction.objectStore('annotations');

            // Import coupons
            if (importData.coupons) {
                for (const coupon of importData.coupons) {
                    delete coupon.id; // Let IndexedDB generate new IDs
                    couponStore.add(coupon);
                }
            }

            // Import annotations
            if (importData.annotations) {
                for (const annotation of importData.annotations) {
                    delete annotation.id; // Let IndexedDB generate new IDs
                    annotationStore.add(annotation);
                }
            }

            return new Promise((resolve, reject) => {
                transaction.oncomplete = () => {
                    console.log('Data imported successfully');
                    resolve(true);
                };

                transaction.onerror = () => {
                    console.error('Failed to import data:', transaction.error);
                    reject(transaction.error);
                };
            });
        } catch (error) {
            console.error('Import failed:', error);
            throw error;
        }
    }

    // Clear all data
    async clearAllData() {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons', 'annotations', 'trainingSessions'], 'readwrite');
            
            transaction.objectStore('coupons').clear();
            transaction.objectStore('annotations').clear();
            transaction.objectStore('trainingSessions').clear();

            transaction.oncomplete = () => {
                console.log('All data cleared');
                resolve(true);
            };

            transaction.onerror = () => {
                console.error('Failed to clear data:', transaction.error);
                reject(transaction.error);
            };
        });
    }

    // Save app settings
    async saveSetting(key, value) {
        await this.init();

        const settingData = { key, value, updatedAt: new Date().toISOString() };

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['settings'], 'readwrite');
            const store = transaction.objectStore('settings');
            const request = store.put(settingData);

            request.onsuccess = () => resolve(true);
            request.onerror = () => reject(request.error);
        });
    }

    // Get app setting
    async getSetting(key, defaultValue = null) {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['settings'], 'readonly');
            const store = transaction.objectStore('settings');
            const request = store.get(key);

            request.onsuccess = () => {
                const result = request.result;
                resolve(result ? result.value : defaultValue);
            };

            request.onerror = () => {
                console.error('Failed to get setting:', request.error);
                resolve(defaultValue);
            };
        });
    }
}

// Global storage instance
const storage = new CouponStorage();

// Utility functions for common operations
const StorageUtils = {
    // Convert file to base64
    fileToBase64: (file) => {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = reject;
            reader.readAsDataURL(file);
        });
    },

    // Create thumbnail from image
    createThumbnail: (imageData, maxWidth = 150, maxHeight = 150) => {
        return new Promise((resolve) => {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            const img = new Image();

            img.onload = () => {
                // Calculate thumbnail dimensions
                let { width, height } = img;
                const ratio = Math.min(maxWidth / width, maxHeight / height);
                width *= ratio;
                height *= ratio;

                canvas.width = width;
                canvas.height = height;

                // Draw thumbnail
                ctx.drawImage(img, 0, 0, width, height);
                resolve(canvas.toDataURL('image/jpeg', 0.8));
            };

            img.src = imageData;
        });
    },

    // Format file size
    formatFileSize: (bytes) => {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    },

    // Generate unique filename
    generateFilename: (originalName) => {
        const timestamp = Date.now();
        const random = Math.random().toString(36).substring(2, 8);
        const extension = originalName.split('.').pop();
        return `coupon_${timestamp}_${random}.${extension}`;
    }
};

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { CouponStorage, StorageUtils, storage };
} else {
    window.CouponStorage = CouponStorage;
    window.StorageUtils = StorageUtils;
    window.storage = storage;
}

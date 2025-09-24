/**
 * Storage Management for Web Training App
 * Handles IndexedDB operations for training data, models, and app state
 */

class TrainingStorage {
    constructor() {
        this.dbName = 'CouponTrainerDB';
        this.dbVersion = 2;
        this.db = null;
        
        this.stores = {
            coupons: 'coupons',
            annotations: 'annotations',
            models: 'models',
            sessions: 'training_sessions',
            settings: 'settings',
            activity: 'activity'
        };
        
        this.init();
    }
    
    async init() {
        try {
            this.db = await this.openDatabase();
            console.log('Training storage initialized successfully');
            this.updateStorageInfo();
        } catch (error) {
            console.error('Failed to initialize storage:', error);
        }
    }
    
    openDatabase() {
        return new Promise((resolve, reject) => {
            const request = indexedDB.open(this.dbName, this.dbVersion);
            
            request.onerror = () => reject(request.error);
            request.onsuccess = () => resolve(request.result);
            
            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                
                // Create stores if they don't exist
                if (!db.objectStoreNames.contains(this.stores.coupons)) {
                    const couponStore = db.createObjectStore(this.stores.coupons, { keyPath: 'id', autoIncrement: true });
                    couponStore.createIndex('filename', 'filename', { unique: false });
                    couponStore.createIndex('uploadDate', 'uploadDate', { unique: false });
                    couponStore.createIndex('annotated', 'annotated', { unique: false });
                }
                
                if (!db.objectStoreNames.contains(this.stores.annotations)) {
                    const annotationStore = db.createObjectStore(this.stores.annotations, { keyPath: 'id', autoIncrement: true });
                    annotationStore.createIndex('couponId', 'couponId', { unique: false });
                    annotationStore.createIndex('fieldType', 'fieldType', { unique: false });
                }
                
                if (!db.objectStoreNames.contains(this.stores.models)) {
                    const modelStore = db.createObjectStore(this.stores.models, { keyPath: 'id', autoIncrement: true });
                    modelStore.createIndex('version', 'version', { unique: true });
                    modelStore.createIndex('createdAt', 'createdAt', { unique: false });
                }
                
                if (!db.objectStoreNames.contains(this.stores.sessions)) {
                    const sessionStore = db.createObjectStore(this.stores.sessions, { keyPath: 'id', autoIncrement: true });
                    sessionStore.createIndex('startTime', 'startTime', { unique: false });
                    sessionStore.createIndex('status', 'status', { unique: false });
                }
                
                if (!db.objectStoreNames.contains(this.stores.settings)) {
                    db.createObjectStore(this.stores.settings, { keyPath: 'key' });
                }
                
                if (!db.objectStoreNames.contains(this.stores.activity)) {
                    const activityStore = db.createObjectStore(this.stores.activity, { keyPath: 'id', autoIncrement: true });
                    activityStore.createIndex('timestamp', 'timestamp', { unique: false });
                    activityStore.createIndex('type', 'type', { unique: false });
                }
            };
        });
    }
    
    // Coupon operations
    async saveCoupon(couponData) {
        return this.saveData(this.stores.coupons, {
            ...couponData,
            uploadDate: new Date().toISOString(),
            annotated: false
        });
    }
    
    async getCoupons() {
        return this.getAllData(this.stores.coupons);
    }
    
    async getCoupon(id) {
        return this.getData(this.stores.coupons, id);
    }
    
    async updateCoupon(id, updates) {
        const coupon = await this.getCoupon(id);
        if (coupon) {
            return this.saveData(this.stores.coupons, { ...coupon, ...updates });
        }
        throw new Error('Coupon not found');
    }
    
    async deleteCoupon(id) {
        return this.deleteData(this.stores.coupons, id);
    }
    
    // Annotation operations
    async saveAnnotations(couponId, annotations) {
        const transaction = this.db.transaction([this.stores.annotations], 'readwrite');
        const store = transaction.objectStore('annotations');
        
        // Delete existing annotations for this coupon
        const index = store.index('couponId');
        const request = index.getAll(couponId);
        
        return new Promise((resolve, reject) => {
            request.onsuccess = async () => {
                const existingAnnotations = request.result;
                
                // Delete existing
                for (const annotation of existingAnnotations) {
                    await store.delete(annotation.id);
                }
                
                // Save new annotations
                const savedAnnotations = [];
                for (const annotation of annotations) {
                    const saveRequest = store.add({
                        couponId,
                        ...annotation,
                        createdAt: new Date().toISOString()
                    });
                    
                    saveRequest.onsuccess = () => {
                        savedAnnotations.push({
                            id: saveRequest.result,
                            couponId,
                            ...annotation
                        });
                        
                        if (savedAnnotations.length === annotations.length) {
                            // Mark coupon as annotated
                            this.updateCoupon(couponId, { annotated: true });
                            resolve(savedAnnotations);
                        }
                    };
                }
                
                if (annotations.length === 0) {
                    resolve([]);
                }
            };
            
            request.onerror = () => reject(request.error);
        });
    }
    
    async getAnnotations(couponId) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([this.stores.annotations], 'readonly');
            const store = transaction.objectStore('annotations');
            const index = store.index('couponId');
            const request = index.getAll(couponId);
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }
    
    // Model operations
    async saveModel(modelData) {
        return this.saveData(this.stores.models, {
            ...modelData,
            createdAt: new Date().toISOString()
        });
    }
    
    async getModels() {
        return this.getAllData(this.stores.models);
    }
    
    async getLatestModel() {
        const models = await this.getModels();
        return models.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))[0];
    }
    
    // Training session operations
    async saveSession(sessionData) {
        return this.saveData(this.stores.sessions, {
            ...sessionData,
            startTime: new Date().toISOString()
        });
    }
    
    async getSessions() {
        return this.getAllData(this.stores.sessions);
    }
    
    async updateSession(id, updates) {
        const session = await this.getData(this.stores.sessions, id);
        if (session) {
            return this.saveData(this.stores.sessions, { ...session, ...updates });
        }
        throw new Error('Session not found');
    }
    
    // Activity logging
    async logActivity(type, title, description = '') {
        return this.saveData(this.stores.activity, {
            type,
            title,
            description,
            timestamp: new Date().toISOString()
        });
    }
    
    async getActivity(limit = 10) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([this.stores.activity], 'readonly');
            const store = transaction.objectStore('activity');
            const index = store.index('timestamp');
            const request = index.openCursor(null, 'prev');
            
            const activities = [];
            let count = 0;
            
            request.onsuccess = (event) => {
                const cursor = event.target.result;
                if (cursor && count < limit) {
                    activities.push(cursor.value);
                    count++;
                    cursor.continue();
                } else {
                    resolve(activities);
                }
            };
            
            request.onerror = () => reject(request.error);
        });
    }
    
    async clearActivity() {
        return this.clearStore(this.stores.activity);
    }
    
    // Settings operations
    async getSetting(key, defaultValue = null) {
        const setting = await this.getData(this.stores.settings, key);
        return setting ? setting.value : defaultValue;
    }
    
    async setSetting(key, value) {
        return this.saveData(this.stores.settings, { key, value });
    }
    
    // Generic database operations
    async saveData(storeName, data) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([storeName], 'readwrite');
            const store = transaction.objectStore(storeName);
            const request = store.put(data);
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }
    
    async getData(storeName, key) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([storeName], 'readonly');
            const store = transaction.objectStore(storeName);
            const request = store.get(key);
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }
    
    async getAllData(storeName) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([storeName], 'readonly');
            const store = transaction.objectStore(storeName);
            const request = store.getAll();
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }
    
    async deleteData(storeName, key) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([storeName], 'readwrite');
            const store = transaction.objectStore(storeName);
            const request = store.delete(key);
            
            request.onsuccess = () => resolve(true);
            request.onerror = () => reject(request.error);
        });
    }
    
    async clearStore(storeName) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([storeName], 'readwrite');
            const store = transaction.objectStore(storeName);
            const request = store.clear();
            
            request.onsuccess = () => resolve(true);
            request.onerror = () => reject(request.error);
        });
    }
    
    // Statistics
    async getStats() {
        const [coupons, annotations, models, sessions] = await Promise.all([
            this.getCoupons(),
            this.getAllData(this.stores.annotations),
            this.getModels(),
            this.getSessions()
        ]);
        
        const annotatedCoupons = coupons.filter(c => c.annotated);
        const completedSessions = sessions.filter(s => s.status === 'completed');
        const latestModel = await this.getLatestModel();
        
        return {
            totalCoupons: coupons.length,
            annotatedCoupons: annotatedCoupons.length,
            totalAnnotations: annotations.length,
            annotationProgress: coupons.length > 0 ? (annotatedCoupons.length / coupons.length) * 100 : 0,
            totalModels: models.length,
            totalSessions: sessions.length,
            completedSessions: completedSessions.length,
            latestModelAccuracy: latestModel?.accuracy || null,
            lastTraining: completedSessions.length > 0 ? completedSessions[completedSessions.length - 1].endTime : null
        };
    }
    
    // Storage info
    async updateStorageInfo() {
        if ('storage' in navigator && 'estimate' in navigator.storage) {
            try {
                const estimate = await navigator.storage.estimate();
                const used = estimate.usage || 0;
                const quota = estimate.quota || 0;
                
                const usedMB = (used / (1024 * 1024)).toFixed(1);
                const quotaMB = (quota / (1024 * 1024)).toFixed(0);
                
                // Update UI elements
                const usedElement = document.getElementById('data-usage');
                if (usedElement) {
                    usedElement.textContent = `${usedMB} MB used`;
                }
                
                const quotaElement = document.getElementById('storage-quota');
                if (quotaElement) {
                    quotaElement.textContent = `${quotaMB} MB`;
                }
                
                return { used: usedMB, quota: quotaMB };
            } catch (error) {
                console.warn('Storage estimate not available:', error);
                return { used: 0, quota: 0 };
            }
        }
        return { used: 0, quota: 0 };
    }
    
    // Export data
    async exportData() {
        const [coupons, annotations, models, sessions] = await Promise.all([
            this.getCoupons(),
            this.getAllData(this.stores.annotations),
            this.getModels(),
            this.getSessions()
        ]);
        
        return {
            exportDate: new Date().toISOString(),
            version: '2.0.0',
            data: {
                coupons,
                annotations,
                models: models.map(m => ({ ...m, modelData: null })), // Exclude large model data
                sessions
            }
        };
    }
    
    // Import data
    async importData(exportedData) {
        if (!exportedData.data) {
            throw new Error('Invalid export data format');
        }
        
        const { coupons, annotations, models, sessions } = exportedData.data;
        
        // Clear existing data
        await Promise.all([
            this.clearStore(this.stores.coupons),
            this.clearStore(this.stores.annotations),
            this.clearStore(this.stores.models),
            this.clearStore(this.stores.sessions)
        ]);
        
        // Import new data
        const results = await Promise.all([
            ...coupons.map(c => this.saveData(this.stores.coupons, c)),
            ...annotations.map(a => this.saveData(this.stores.annotations, a)),
            ...models.map(m => this.saveData(this.stores.models, m)),
            ...sessions.map(s => this.saveData(this.stores.sessions, s))
        ]);
        
        await this.logActivity('import', 'Data imported successfully', `Imported ${coupons.length} coupons, ${annotations.length} annotations`);
        
        return results;
    }
}

// Initialize storage
const trainingStorage = new TrainingStorage();

// Make it globally available
window.trainingStorage = trainingStorage;

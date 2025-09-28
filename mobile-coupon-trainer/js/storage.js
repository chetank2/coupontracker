// Enhanced Local Storage Management for Multi-Coupon Trainer PWA
// Handles offline data storage using IndexedDB with two-stage annotation support

class EnhancedCouponStorage {
    constructor() {
        this.dbName = 'MultiCouponTrainerDB';
        this.dbVersion = 2; // Upgraded for multi-coupon support
        this.db = null;
        this.isInitialized = false;
    }

    // Initialize IndexedDB with enhanced schema
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
                console.log('Enhanced database initialized successfully');
                resolve(true);
            };

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                console.log('Upgrading database to support multi-coupon annotation...');

                // Create coupons store (enhanced)
                if (!db.objectStoreNames.contains('coupons')) {
                    const couponStore = db.createObjectStore('coupons', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    couponStore.createIndex('filename', 'filename', { unique: false });
                    couponStore.createIndex('uploadDate', 'uploadDate', { unique: false });
                    couponStore.createIndex('status', 'status', { unique: false });
                    couponStore.createIndex('imageClassification', 'imageClassification', { unique: false });
                }

                // Create annotations store (enhanced)
                if (!db.objectStoreNames.contains('annotations')) {
                    const annotationStore = db.createObjectStore('annotations', { 
                        keyPath: 'id', 
                        autoIncrement: true 
                    });
                    annotationStore.createIndex('couponId', 'couponId', { unique: false });
                    annotationStore.createIndex('fieldType', 'fieldType', { unique: false });
                    annotationStore.createIndex('instanceId', 'instanceId', { unique: false });
                    annotationStore.createIndex('annotationStage', 'annotationStage', { unique: false });
                    annotationStore.createIndex('createdAt', 'createdAt', { unique: false });
                }

                // NEW: Coupon instances store for Stage 1 boundaries
                if (!db.objectStoreNames.contains('coupon_instances')) {
                    const instanceStore = db.createObjectStore('coupon_instances', {
                        keyPath: 'instanceId'
                    });
                    instanceStore.createIndex('couponId', 'couponId', { unique: false });
                    instanceStore.createIndex('status', 'status', { unique: false });
                    instanceStore.createIndex('createdAt', 'createdAt', { unique: false });
                }

                // NEW: Training datasets store
                if (!db.objectStoreNames.contains('training_datasets')) {
                    const datasetStore = db.createObjectStore('training_datasets', {
                        keyPath: 'datasetId'
                    });
                    datasetStore.createIndex('createdAt', 'createdAt', { unique: false });
                    datasetStore.createIndex('modelType', 'modelType', { unique: false });
                    datasetStore.createIndex('version', 'version', { unique: false });
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

    // Enhanced coupon saving with multi-coupon metadata
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
                type: 'image',
                // Multi-coupon annotation support
                annotationStage: 'stage1_coupons', // 'stage1_coupons' or 'stage2_fields'
                imageClassification: 'unknown', // 'single', 'multi_grid', 'scrollable'
                totalCoupons: 0,
                imageWidth: null,
                imageHeight: null
            }
        };

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readwrite');
            const store = transaction.objectStore('coupons');
            const request = store.add(couponData);

            request.onsuccess = () => {
                console.log('Enhanced coupon saved:', filename);
                resolve(request.result); // Returns the generated ID
            };

            request.onerror = () => {
                console.error('Failed to save coupon:', request.error);
                reject(request.error);
            };
        });
    }

    // NEW: Save coupon instance metadata (Stage 1)
    async saveCouponInstance(couponId, instanceData) {
        await this.init();
        
        const instanceRecord = {
            couponId: couponId,
            instanceId: instanceData.instanceId,
            boundingBox: instanceData.boundingBox,
            status: instanceData.status, // 'coupon_complete', 'coupon_partial_top', 'coupon_partial_bottom'
            confidence: instanceData.confidence || 1.0,
            createdAt: new Date().toISOString()
        };
        
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupon_instances'], 'readwrite');
            const store = transaction.objectStore('coupon_instances');
            const request = store.put(instanceRecord); // Use put to allow updates
            
            request.onsuccess = () => {
                console.log('Coupon instance saved:', instanceData.instanceId);
                resolve(request.result);
            };
            
            request.onerror = () => {
                console.error('Failed to save coupon instance:', request.error);
                reject(request.error);
            };
        });
    }

    // NEW: Get coupon instances for a specific coupon
    async getCouponInstances(couponId) {
        await this.init();
        
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupon_instances'], 'readonly');
            const store = transaction.objectStore('coupon_instances');
            const index = store.index('couponId');
            const request = index.getAll(couponId);
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }

    // NEW: Delete coupon instance
    async deleteCouponInstance(instanceId) {
        await this.init();
        
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupon_instances', 'annotations'], 'readwrite');
            
            // Delete instance
            const instanceStore = transaction.objectStore('coupon_instances');
            instanceStore.delete(instanceId);
            
            // Delete associated field annotations
            const annotationStore = transaction.objectStore('annotations');
            const index = annotationStore.index('instanceId');
            const request = index.getAll(instanceId);
            
            request.onsuccess = () => {
                const annotations = request.result;
                annotations.forEach(annotation => {
                    annotationStore.delete(annotation.id);
                });
            };
            
            transaction.oncomplete = () => {
                console.log('Coupon instance and annotations deleted:', instanceId);
                resolve(true);
            };
            
            transaction.onerror = () => reject(transaction.error);
        });
    }

    // Enhanced annotation saving with stage and instance support
    async saveAnnotation(couponId, annotation) {
        await this.init();

        const annotationData = {
            couponId: couponId,
            fieldType: annotation.fieldType,
            boundingBox: annotation.boundingBox,
            confidence: annotation.confidence || 1.0,
            instanceId: annotation.instanceId || null, // For Stage 2 field annotations
            annotationStage: annotation.annotationStage || 'stage2_fields', // 'stage1_coupons' or 'stage2_fields'
            text: annotation.text || null, // For OCR results
            createdAt: new Date().toISOString(),
            status: 'pending_upload'
        };

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['annotations'], 'readwrite');
            const store = transaction.objectStore('annotations');
            const request = store.add(annotationData);

            request.onsuccess = () => {
                console.log('Enhanced annotation saved for coupon:', couponId);
                resolve(request.result);
            };

            request.onerror = () => {
                console.error('Failed to save annotation:', request.error);
                reject(request.error);
            };
        });
    }

    // NEW: Update coupon metadata (classification, stage, dimensions)
    async updateCouponMetadata(couponId, metadata) {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['coupons'], 'readwrite');
            const store = transaction.objectStore('coupons');
            
            store.get(couponId).onsuccess = (event) => {
                const coupon = event.target.result;
                if (coupon) {
                    // Update metadata
                    coupon.metadata = { ...coupon.metadata, ...metadata };
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

    // NEW: Export complete training dataset in two-stage format
    async exportTrainingDataset() {
        await this.init();
        
        console.log('🔄 Exporting complete training dataset...');
        
        const coupons = await this.getAllCoupons();
        const trainingDataset = {
            version: '2.0.0',
            type: 'multi_coupon_training',
            exportDate: new Date().toISOString(),
            totalImages: coupons.length,
            stage1_coupon_detection: [],
            stage2_field_detection: [],
            statistics: {
                single_coupon: 0,
                multi_grid: 0,
                scrollable: 0,
                unknown: 0,
                total_annotations: 0,
                total_instances: 0
            }
        };
        
        for (const coupon of coupons) {
            if (!coupon.annotations || coupon.annotations.length === 0) {
                console.log(`Skipping coupon ${coupon.filename} - no annotations`);
                continue;
            }
            
            const instances = await this.getCouponInstances(coupon.id);
            const annotations = await this.getAnnotations(coupon.id);
            const imageClassification = coupon.metadata?.imageClassification || 'unknown';
            
            console.log(`Processing ${coupon.filename}: ${instances.length} instances, ${annotations.length} annotations`);
            
            // Stage 1 data: Coupon boundaries
            instances.forEach(instance => {
                trainingDataset.stage1_coupon_detection.push({
                    image_id: coupon.filename,
                    image_width: coupon.metadata?.imageWidth || 640,
                    image_height: coupon.metadata?.imageHeight || 640,
                    instance_id: instance.instanceId,
                    class_name: instance.status,
                    bounding_box: instance.boundingBox,
                    confidence: instance.confidence,
                    image_classification: imageClassification,
                    image_data: coupon.imageData // Include for training
                });
                trainingDataset.statistics.total_instances++;
            });
            
            // Stage 2 data: Field annotations
            annotations.forEach(annotation => {
                if (annotation.annotationStage === 'stage2_fields') {
                    trainingDataset.stage2_field_detection.push({
                        image_id: coupon.filename,
                        instance_id: annotation.instanceId || 'default',
                        field_type: annotation.fieldType,
                        bounding_box: annotation.boundingBox,
                        confidence: annotation.confidence || 1.0,
                        text_content: annotation.text || null,
                        image_data: coupon.imageData // Include for training
                    });
                    trainingDataset.statistics.total_annotations++;
                }
            });
            
            // Update statistics
            trainingDataset.statistics[imageClassification]++;
        }
        
        // Save dataset
        const datasetId = `dataset_${Date.now()}`;
        await this.saveTrainingDataset(datasetId, trainingDataset);
        
        console.log('✅ Training dataset exported:', {
            totalImages: trainingDataset.totalImages,
            totalInstances: trainingDataset.statistics.total_instances,
            totalAnnotations: trainingDataset.statistics.total_annotations
        });
        
        return trainingDataset;
    }

    // NEW: Save training dataset
    async saveTrainingDataset(datasetId, dataset) {
        await this.init();
        
        const datasetRecord = {
            datasetId: datasetId,
            dataset: dataset,
            createdAt: new Date().toISOString(),
            modelType: 'multi_coupon_yolo',
            version: dataset.version || '2.0.0'
        };
        
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['training_datasets'], 'readwrite');
            const store = transaction.objectStore('training_datasets');
            const request = store.put(datasetRecord);
            
            request.onsuccess = () => {
                console.log('Training dataset saved:', datasetId);
                resolve(request.result);
            };
            request.onerror = () => reject(request.error);
        });
    }

    // NEW: Get all training datasets
    async getTrainingDatasets() {
        await this.init();
        
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction(['training_datasets'], 'readonly');
            const store = transaction.objectStore('training_datasets');
            const request = store.getAll();
            
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
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
                const transaction = this.db.transaction(['coupons', 'annotations', 'coupon_instances'], 'readwrite');
                
                // Delete coupon
                const couponStore = transaction.objectStore('coupons');
                couponStore.delete(id);
                
                // Delete associated annotations
                const annotationStore = transaction.objectStore('annotations');
                const annotationIndex = annotationStore.index('couponId');
                const annotations = await new Promise((res, rej) => {
                    const req = annotationIndex.getAll(id);
                    req.onsuccess = () => res(req.result);
                    req.onerror = () => rej(req.error);
                });
                
                annotations.forEach(annotation => {
                    annotationStore.delete(annotation.id);
                });

                // Delete associated coupon instances
                const instanceStore = transaction.objectStore('coupon_instances');
                const instanceIndex = instanceStore.index('couponId');
                const instances = await new Promise((res, rej) => {
                    const req = instanceIndex.getAll(id);
                    req.onsuccess = () => res(req.result);
                    req.onerror = () => rej(req.error);
                });
                
                instances.forEach(instance => {
                    instanceStore.delete(instance.instanceId);
                });

                transaction.oncomplete = () => {
                    console.log('Coupon, annotations, and instances deleted:', id);
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

    // Enhanced storage statistics
    async getStorageStats() {
        await this.init();

        try {
            const coupons = await this.getAllCoupons();
            const datasets = await this.getTrainingDatasets();
            
            const totalCoupons = coupons.length;
            const annotatedCoupons = coupons.filter(c => c.status === 'annotated').length;
            const pendingUpload = coupons.filter(c => c.status === 'pending_upload').length;
            
            // Count by image classification
            const singleCoupons = coupons.filter(c => c.metadata?.imageClassification === 'single').length;
            const multiCoupons = coupons.filter(c => c.metadata?.imageClassification === 'multi_grid').length;
            const scrollableCoupons = coupons.filter(c => c.metadata?.imageClassification === 'scrollable').length;
            
            // Calculate total instances and annotations
            let totalInstances = 0;
            let totalAnnotations = 0;
            
            for (const coupon of coupons) {
                const instances = await this.getCouponInstances(coupon.id);
                const annotations = await this.getAnnotations(coupon.id);
                totalInstances += instances.length;
                totalAnnotations += annotations.length;
            }
            
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
                singleCoupons,
                multiCoupons,
                scrollableCoupons,
                totalInstances,
                totalAnnotations,
                totalDatasets: datasets.length,
                totalSize,
                storageUsed,
                storageQuota,
                storageUsedMB: Math.round(storageUsed / (1024 * 1024)),
                storageQuotaMB: Math.round(storageQuota / (1024 * 1024))
            };
        } catch (error) {
            console.error('Failed to get enhanced storage stats:', error);
            return {
                totalCoupons: 0,
                annotatedCoupons: 0,
                pendingUpload: 0,
                singleCoupons: 0,
                multiCoupons: 0,
                scrollableCoupons: 0,
                totalInstances: 0,
                totalAnnotations: 0,
                totalDatasets: 0,
                totalSize: 0,
                storageUsed: 0,
                storageQuota: 0,
                storageUsedMB: 0,
                storageQuotaMB: 0
            };
        }
    }

    // Clear all data
    async clearAllData() {
        await this.init();

        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([
                'coupons', 
                'annotations', 
                'coupon_instances',
                'training_datasets',
                'trainingSessions'
            ], 'readwrite');
            
            transaction.objectStore('coupons').clear();
            transaction.objectStore('annotations').clear();
            transaction.objectStore('coupon_instances').clear();
            transaction.objectStore('training_datasets').clear();
            transaction.objectStore('trainingSessions').clear();

            transaction.oncomplete = () => {
                console.log('All enhanced data cleared');
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

// Global enhanced storage instance
const storage = new EnhancedCouponStorage();

// Enhanced utility functions
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

    // Get image dimensions
    getImageDimensions: (imageData) => {
        return new Promise((resolve) => {
            const img = new Image();
            img.onload = () => {
                resolve({ width: img.width, height: img.height });
            };
            img.src = imageData;
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
    },

    // Generate unique instance ID
    generateInstanceId: () => {
        const timestamp = Date.now();
        const random = Math.random().toString(36).substring(2, 9);
        return `instance_${timestamp}_${random}`;
    },

    // Download JSON data as file
    downloadJSON: (data, filename) => {
        const blob = new Blob([JSON.stringify(data, null, 2)], {
            type: 'application/json'
        });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }
};

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { EnhancedCouponStorage, StorageUtils, storage };
} else {
    window.EnhancedCouponStorage = EnhancedCouponStorage;
    window.StorageUtils = StorageUtils;
    window.storage = storage;
}
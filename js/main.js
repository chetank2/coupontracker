// Main JavaScript for Coupon Trainer PWA

class CouponTrainerApp {
    constructor() {
        this.isOnline = navigator.onLine;
        this.deferredPrompt = null;
        this.installBannerShown = false;
        
        this.init();
    }

    async init() {
        console.log('Initializing Coupon Trainer PWA...');
        
        // Initialize storage
        try {
            await storage.init();
            console.log('Storage initialized successfully');
        } catch (error) {
            console.error('Failed to initialize storage:', error);
            this.showToast('Storage initialization failed', 'error');
        }
        
        this.setupEventListeners();
        this.setupPWAFeatures();
        this.updateConnectionStatus();
        this.loadDashboardData();
        
        console.log('App initialized successfully');
    }

    setupEventListeners() {
        // Online/offline status
        window.addEventListener('online', () => {
            this.isOnline = true;
            this.updateConnectionStatus();
            this.showToast('Back online', 'success');
        });

        window.addEventListener('offline', () => {
            this.isOnline = false;
            this.updateConnectionStatus();
            this.showToast('You are offline', 'warning');
        });

        // Dashboard action buttons
        const trainModelBtn = document.getElementById('train-model-btn');
        if (trainModelBtn) {
            trainModelBtn.addEventListener('click', () => this.startTraining());
        }

        const exportDataBtn = document.getElementById('export-data-btn');
        if (exportDataBtn) {
            exportDataBtn.addEventListener('click', () => this.exportData());
        }

        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey || e.metaKey) {
                switch (e.key) {
                    case 'u':
                        e.preventDefault();
                        window.location.href = '/upload.html';
                        break;
                    case 'd':
                        e.preventDefault();
                        window.location.href = '/data.html';
                        break;
                    case 'e':
                        e.preventDefault();
                        this.exportData();
                        break;
                }
            }
        });
    }

    setupPWAFeatures() {
        // PWA install prompt
        window.addEventListener('beforeinstallprompt', (e) => {
            e.preventDefault();
            this.deferredPrompt = e;
            this.showInstallBanner();
        });

        // Install banner handlers
        const installBtn = document.getElementById('install-button');
        const dismissBtn = document.getElementById('dismiss-install');
        
        if (installBtn) {
            installBtn.addEventListener('click', () => this.installPWA());
        }
        
        if (dismissBtn) {
            dismissBtn.addEventListener('click', () => this.dismissInstallBanner());
        }

        // Check if already installed
        window.addEventListener('appinstalled', () => {
            console.log('PWA was installed');
            this.dismissInstallBanner();
            this.showToast('App installed successfully!', 'success');
        });

        // Handle PWA display mode
        if (window.matchMedia('(display-mode: standalone)').matches) {
            console.log('Running in standalone mode');
            document.body.classList.add('standalone');
        }
    }

    showInstallBanner() {
        if (this.installBannerShown) return;
        
        const banner = document.getElementById('install-banner');
        if (banner) {
            banner.classList.remove('hidden');
            this.installBannerShown = true;
            
            // Auto-hide after 10 seconds
            setTimeout(() => {
                this.dismissInstallBanner();
            }, 10000);
        }
    }

    dismissInstallBanner() {
        const banner = document.getElementById('install-banner');
        if (banner) {
            banner.classList.add('hidden');
        }
    }

    async installPWA() {
        if (!this.deferredPrompt) return;
        
        this.deferredPrompt.prompt();
        const { outcome } = await this.deferredPrompt.userChoice;
        
        if (outcome === 'accepted') {
            console.log('User accepted the install prompt');
        } else {
            console.log('User dismissed the install prompt');
        }
        
        this.deferredPrompt = null;
        this.dismissInstallBanner();
    }

    updateConnectionStatus() {
        const indicator = document.getElementById('status-indicator');
        const statusText = document.getElementById('status-text');
        
        if (indicator) {
            indicator.className = `status-indicator ${this.isOnline ? 'online' : 'offline'}`;
        }
        
        if (statusText) {
            statusText.textContent = this.isOnline ? 'Online' : 'Offline';
        }
    }

    async loadDashboardData() {
        try {
            const stats = await storage.getStorageStats();
            this.updateDashboardStats(stats);
            this.loadRecentActivity();
            this.updateStorageInfo(stats);
        } catch (error) {
            console.error('Failed to load dashboard data:', error);
        }
    }

    updateDashboardStats(stats) {
        const elements = {
            'total-coupons': stats.totalCoupons,
            'annotated-count': stats.annotatedCoupons,
            'pending-upload': stats.pendingUpload,
            'model-accuracy': '--' // Will be updated when training is implemented
        };

        Object.entries(elements).forEach(([id, value]) => {
            const element = document.getElementById(id);
            if (element) {
                element.textContent = value;
            }
        });
    }

    async loadRecentActivity() {
        const activityList = document.getElementById('activity-list');
        if (!activityList) return;

        try {
            const coupons = await storage.getAllCoupons();
            
            // Clear existing items except welcome message
            const welcomeItem = activityList.querySelector('.activity-item');
            activityList.innerHTML = '';
            
            if (coupons.length === 0) {
                activityList.appendChild(welcomeItem);
                return;
            }

            // Show recent coupons (last 5)
            const recentCoupons = coupons
                .sort((a, b) => new Date(b.uploadDate) - new Date(a.uploadDate))
                .slice(0, 5);

            recentCoupons.forEach(coupon => {
                const activityItem = this.createActivityItem(coupon);
                activityList.appendChild(activityItem);
            });

        } catch (error) {
            console.error('Failed to load recent activity:', error);
        }
    }

    createActivityItem(coupon) {
        const item = document.createElement('div');
        item.className = 'activity-item';
        
        const statusIcons = {
            'uploaded': '📤',
            'annotated': '✅',
            'pending_upload': '⏳'
        };
        
        const timeAgo = this.formatTimeAgo(new Date(coupon.uploadDate));
        
        item.innerHTML = `
            <div class="activity-icon">${statusIcons[coupon.status] || '📱'}</div>
            <div class="activity-content">
                <div class="activity-title">${coupon.filename}</div>
                <div class="activity-time">${timeAgo}</div>
            </div>
        `;
        
        return item;
    }

    formatTimeAgo(date) {
        const now = new Date();
        const diffInSeconds = Math.floor((now - date) / 1000);
        
        if (diffInSeconds < 60) return 'Just now';
        if (diffInSeconds < 3600) return `${Math.floor(diffInSeconds / 60)} minutes ago`;
        if (diffInSeconds < 86400) return `${Math.floor(diffInSeconds / 3600)} hours ago`;
        return `${Math.floor(diffInSeconds / 86400)} days ago`;
    }

    updateStorageInfo(stats) {
        const usedElement = document.getElementById('storage-used');
        const quotaElement = document.getElementById('storage-quota');
        
        if (usedElement) {
            usedElement.textContent = `${stats.storageUsedMB} MB`;
        }
        
        if (quotaElement) {
            quotaElement.textContent = stats.storageQuotaMB > 0 ? `${stats.storageQuotaMB} MB` : '-- MB';
        }
    }

    async startTraining() {
        const stats = await storage.getStorageStats();
        
        if (stats.annotatedCoupons === 0) {
            this.showToast('No annotated coupons found. Upload and annotate some coupons first.', 'warning');
            return;
        }

        this.showLoadingOverlay('Preparing training data...');
        
        try {
            // Simulate training process (replace with actual ML training)
            await this.simulateTraining(stats.annotatedCoupons);
            
            this.hideLoadingOverlay();
            this.showToast('Model training completed!', 'success');
            
            // Update model accuracy display
            const accuracyElement = document.getElementById('model-accuracy');
            if (accuracyElement) {
                const accuracy = Math.floor(Math.random() * 15) + 85; // Simulate 85-100% accuracy
                accuracyElement.textContent = `${accuracy}%`;
            }
            
        } catch (error) {
            this.hideLoadingOverlay();
            this.showToast('Training failed: ' + error.message, 'error');
        }
    }

    async simulateTraining(annotatedCount) {
        const steps = [
            'Loading training data...',
            'Preprocessing images...',
            'Training detection model...',
            'Validating model...',
            'Optimizing for mobile...',
            'Finalizing model...'
        ];
        
        for (let i = 0; i < steps.length; i++) {
            this.updateLoadingText(steps[i]);
            await this.delay(1000 + Math.random() * 2000); // Random delay 1-3 seconds
        }
    }

    async exportData() {
        try {
            this.showLoadingOverlay('Preparing export...');
            
            const exportData = await storage.exportData();
            
            if (exportData.coupons.length === 0) {
                this.hideLoadingOverlay();
                this.showToast('No data to export', 'warning');
                return;
            }
            
            // Create downloadable file
            const blob = new Blob([JSON.stringify(exportData, null, 2)], {
                type: 'application/json'
            });
            
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `coupon-training-data-${new Date().toISOString().split('T')[0]}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            
            this.hideLoadingOverlay();
            this.showToast(`Exported ${exportData.coupons.length} coupons`, 'success');
            
        } catch (error) {
            this.hideLoadingOverlay();
            this.showToast('Export failed: ' + error.message, 'error');
        }
    }

    showLoadingOverlay(text = 'Loading...') {
        const overlay = document.getElementById('loading-overlay');
        const loadingText = document.getElementById('loading-text');
        
        if (overlay) {
            overlay.classList.remove('hidden');
        }
        
        if (loadingText) {
            loadingText.textContent = text;
        }
    }

    hideLoadingOverlay() {
        const overlay = document.getElementById('loading-overlay');
        if (overlay) {
            overlay.classList.add('hidden');
        }
    }

    updateLoadingText(text) {
        const loadingText = document.getElementById('loading-text');
        if (loadingText) {
            loadingText.textContent = text;
        }
    }

    showToast(message, type = 'info', duration = 3000) {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        
        const container = document.getElementById('toast-container');
        if (container) {
            container.appendChild(toast);
            
            setTimeout(() => {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, duration);
        }
    }

    delay(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    // Utility methods for other pages
    static formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    static formatDate(dateString) {
        return new Date(dateString).toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    }
}

// Initialize app when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.couponTrainerApp = new CouponTrainerApp();
});

// Global utility functions
window.showToast = (message, type, duration) => {
    if (window.couponTrainerApp) {
        window.couponTrainerApp.showToast(message, type, duration);
    }
};

window.showLoading = (text) => {
    if (window.couponTrainerApp) {
        window.couponTrainerApp.showLoadingOverlay(text);
    }
};

window.hideLoading = () => {
    if (window.couponTrainerApp) {
        window.couponTrainerApp.hideLoadingOverlay();
    }
};

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = CouponTrainerApp;
} else {
    window.CouponTrainerApp = CouponTrainerApp;
}

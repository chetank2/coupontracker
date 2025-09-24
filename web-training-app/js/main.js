/**
 * Main Application Controller
 * Handles global app functionality and utilities
 */

class CouponTrainerApp {
    constructor() {
        this.version = '2.0.0';
        this.initialized = false;
        
        this.init();
    }
    
    async init() {
        if (this.initialized) return;
        
        console.log(`Coupon Trainer v${this.version} initializing...`);
        
        // Wait for storage to be ready
        await this.waitForStorage();
        
        // Setup global event listeners
        this.setupGlobalEvents();
        
        // Update storage info periodically
        setInterval(() => {
            if (window.trainingStorage) {
                window.trainingStorage.updateStorageInfo();
            }
        }, 30000); // Every 30 seconds
        
        this.initialized = true;
        console.log('Coupon Trainer initialized successfully');
    }
    
    async waitForStorage() {
        return new Promise((resolve) => {
            const checkStorage = () => {
                if (window.trainingStorage && window.trainingStorage.db) {
                    resolve();
                } else {
                    setTimeout(checkStorage, 100);
                }
            };
            checkStorage();
        });
    }
    
    setupGlobalEvents() {
        // Handle keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            // Ctrl/Cmd + K for quick search (future feature)
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                this.showQuickSearch();
            }
            
            // Escape to close modals
            if (e.key === 'Escape') {
                this.closeActiveModal();
            }
        });
        
        // Handle network status
        window.addEventListener('online', () => {
            this.updateConnectionStatus(true);
        });
        
        window.addEventListener('offline', () => {
            this.updateConnectionStatus(false);
        });
        
        // Update connection status on load
        this.updateConnectionStatus(navigator.onLine);
        
        // Handle visibility changes (for performance optimization)
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                this.onAppVisible();
            } else {
                this.onAppHidden();
            }
        });
        
        // Handle beforeunload for unsaved changes
        window.addEventListener('beforeunload', (e) => {
            if (this.hasUnsavedChanges()) {
                e.preventDefault();
                e.returnValue = '';
            }
        });
    }
    
    updateConnectionStatus(isOnline) {
        const statusIndicator = document.getElementById('status-indicator');
        const statusText = document.getElementById('status-text');
        
        if (statusIndicator) {
            statusIndicator.className = `status-indicator ${isOnline ? 'online' : 'offline'}`;
        }
        
        if (statusText) {
            statusText.textContent = isOnline ? 'Online' : 'Offline';
        }
        
        // Show toast for status changes
        if (this.initialized) {
            this.showToast(
                isOnline ? 'success' : 'warning',
                isOnline ? 'Connection restored' : 'Working offline'
            );
        }
    }
    
    onAppVisible() {
        // Refresh data when app becomes visible
        if (window.trainingDashboard) {
            window.trainingDashboard.loadStats();
        }
    }
    
    onAppHidden() {
        // Perform cleanup or save state when app is hidden
        this.saveAppState();
    }
    
    hasUnsavedChanges() {
        // Check for unsaved changes in various components
        return false; // Implement based on specific needs
    }
    
    saveAppState() {
        // Save current app state to localStorage
        const state = {
            lastVisit: new Date().toISOString(),
            version: this.version
        };
        
        localStorage.setItem('couponTrainerState', JSON.stringify(state));
    }
    
    loadAppState() {
        try {
            const stateJson = localStorage.getItem('couponTrainerState');
            return stateJson ? JSON.parse(stateJson) : null;
        } catch (error) {
            console.warn('Failed to load app state:', error);
            return null;
        }
    }
    
    showQuickSearch() {
        // Future feature: Quick search modal
        this.showToast('info', 'Quick search coming soon!');
    }
    
    closeActiveModal() {
        // Close any active modal
        const activeModal = document.querySelector('.modal.active');
        if (activeModal) {
            activeModal.classList.remove('active');
        }
    }
    
    // Utility methods
    showLoading(text = 'Loading...') {
        const overlay = document.getElementById('loading-overlay');
        const loadingText = document.getElementById('loading-text');
        
        if (loadingText) loadingText.textContent = text;
        if (overlay) overlay.classList.remove('hidden');
    }
    
    hideLoading() {
        const overlay = document.getElementById('loading-overlay');
        if (overlay) overlay.classList.add('hidden');
    }
    
    showToast(type, message, duration = 5000) {
        const container = document.getElementById('toast-container');
        if (!container) {
            console.log(`Toast (${type}): ${message}`);
            return;
        }
        
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        
        const icons = {
            success: '✅',
            error: '❌',
            warning: '⚠️',
            info: 'ℹ️'
        };
        
        toast.innerHTML = `
            <div style="display: flex; align-items: center; gap: 12px;">
                <span style="font-size: 18px; flex-shrink: 0;">
                    ${icons[type] || icons.info}
                </span>
                <div style="flex: 1;">
                    <div style="font-weight: 600; margin-bottom: 2px;">
                        ${type.charAt(0).toUpperCase() + type.slice(1)}
                    </div>
                    <div style="font-size: 14px; opacity: 0.9;">
                        ${message}
                    </div>
                </div>
                <button onclick="this.parentElement.parentElement.remove()" 
                        style="background: none; border: none; font-size: 18px; cursor: pointer; opacity: 0.7;">
                    ×
                </button>
            </div>
        `;
        
        container.appendChild(toast);
        
        // Auto remove after duration
        setTimeout(() => {
            if (toast.parentElement) {
                toast.remove();
            }
        }, duration);
    }
    
    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
    
    formatDate(dateString, options = {}) {
        if (!dateString) return 'Never';
        
        const date = new Date(dateString);
        const defaultOptions = {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        };
        
        return date.toLocaleDateString('en-US', { ...defaultOptions, ...options });
    }
    
    formatRelativeTime(dateString) {
        if (!dateString) return 'Never';
        
        const now = new Date();
        const date = new Date(dateString);
        const diffMs = now - date;
        const diffSecs = Math.floor(diffMs / 1000);
        const diffMins = Math.floor(diffSecs / 60);
        const diffHours = Math.floor(diffMins / 60);
        const diffDays = Math.floor(diffHours / 24);
        
        if (diffSecs < 60) return 'Just now';
        if (diffMins < 60) return `${diffMins} minute${diffMins !== 1 ? 's' : ''} ago`;
        if (diffHours < 24) return `${diffHours} hour${diffHours !== 1 ? 's' : ''} ago`;
        if (diffDays < 7) return `${diffDays} day${diffDays !== 1 ? 's' : ''} ago`;
        if (diffDays < 30) return `${Math.floor(diffDays / 7)} week${Math.floor(diffDays / 7) !== 1 ? 's' : ''} ago`;
        
        return this.formatDate(dateString, { year: 'numeric', month: 'short', day: 'numeric' });
    }
    
    debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }
    
    throttle(func, limit) {
        let inThrottle;
        return function() {
            const args = arguments;
            const context = this;
            if (!inThrottle) {
                func.apply(context, args);
                inThrottle = true;
                setTimeout(() => inThrottle = false, limit);
            }
        };
    }
    
    async downloadJSON(data, filename) {
        try {
            const blob = new Blob([JSON.stringify(data, null, 2)], 
                { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            
            return true;
        } catch (error) {
            console.error('Download failed:', error);
            return false;
        }
    }
    
    async copyToClipboard(text) {
        try {
            await navigator.clipboard.writeText(text);
            this.showToast('success', 'Copied to clipboard');
            return true;
        } catch (error) {
            console.error('Copy failed:', error);
            this.showToast('error', 'Failed to copy to clipboard');
            return false;
        }
    }
    
    generateId() {
        return Date.now().toString(36) + Math.random().toString(36).substr(2);
    }
    
    validateEmail(email) {
        const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return re.test(email);
    }
    
    sanitizeHTML(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }
    
    // Performance monitoring
    measurePerformance(name, fn) {
        const start = performance.now();
        const result = fn();
        const end = performance.now();
        
        console.log(`Performance: ${name} took ${end - start} milliseconds`);
        return result;
    }
    
    // Error handling
    handleError(error, context = 'Unknown') {
        console.error(`Error in ${context}:`, error);
        
        // Log error for analytics (in production)
        this.logError(error, context);
        
        // Show user-friendly message
        this.showToast('error', 'An error occurred. Please try again.');
    }
    
    logError(error, context) {
        // In production, send to error tracking service
        const errorLog = {
            message: error.message,
            stack: error.stack,
            context,
            timestamp: new Date().toISOString(),
            userAgent: navigator.userAgent,
            url: window.location.href
        };
        
        console.log('Error logged:', errorLog);
    }
}

// Initialize app
const app = new CouponTrainerApp();

// Make globally available
window.CouponTrainerApp = app;

// Export utility functions for global use
window.showLoading = (text) => app.showLoading(text);
window.hideLoading = () => app.hideLoading();
window.showToast = (type, message, duration) => app.showToast(type, message, duration);

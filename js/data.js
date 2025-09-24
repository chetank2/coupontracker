// Data management functionality for Coupon Trainer PWA

class DataManager {
    constructor() {
        this.allCoupons = [];
        this.filteredCoupons = [];
        this.selectedItems = new Set();
        this.currentFilters = {
            fieldType: '',
            status: ''
        };
        
        this.init();
    }

    async init() {
        console.log('Initializing Data Manager...');
        
        this.setupEventListeners();
        await this.loadData();
        this.updateDisplay();
        
        console.log('Data Manager initialized');
    }

    setupEventListeners() {
        // Filter toggle
        const filterToggle = document.getElementById('filter-toggle');
        if (filterToggle) {
            filterToggle.addEventListener('click', () => {
                this.toggleFilterPanel();
            });
        }

        // Filter controls
        const applyFiltersBtn = document.getElementById('apply-filters');
        const clearFiltersBtn = document.getElementById('clear-filters');
        
        if (applyFiltersBtn) {
            applyFiltersBtn.addEventListener('click', () => {
                this.applyFilters();
            });
        }
        
        if (clearFiltersBtn) {
            clearFiltersBtn.addEventListener('click', () => {
                this.clearFilters();
            });
        }

        // List controls
        const selectAllBtn = document.getElementById('select-all');
        const deleteSelectedBtn = document.getElementById('delete-selected');
        const exportSelectedBtn = document.getElementById('export-selected');
        
        if (selectAllBtn) {
            selectAllBtn.addEventListener('click', () => {
                this.toggleSelectAll();
            });
        }
        
        if (deleteSelectedBtn) {
            deleteSelectedBtn.addEventListener('click', () => {
                this.deleteSelected();
            });
        }
        
        if (exportSelectedBtn) {
            exportSelectedBtn.addEventListener('click', () => {
                this.exportSelected();
            });
        }

        // Modal controls
        const modalClose = document.getElementById('modal-close');
        const bulkModalClose = document.getElementById('bulk-modal-close');
        const closePreview = document.getElementById('close-preview');
        
        if (modalClose) {
            modalClose.addEventListener('click', () => {
                this.closeModal('preview-modal');
            });
        }
        
        if (bulkModalClose) {
            bulkModalClose.addEventListener('click', () => {
                this.closeModal('bulk-modal');
            });
        }
        
        if (closePreview) {
            closePreview.addEventListener('click', () => {
                this.closeModal('preview-modal');
            });
        }

        // Bulk action buttons
        const bulkExportBtn = document.getElementById('bulk-export');
        const bulkDeleteBtn = document.getElementById('bulk-delete');
        const bulkSyncBtn = document.getElementById('bulk-sync');
        
        if (bulkExportBtn) {
            bulkExportBtn.addEventListener('click', () => {
                this.bulkExport();
            });
        }
        
        if (bulkDeleteBtn) {
            bulkDeleteBtn.addEventListener('click', () => {
                this.bulkDelete();
            });
        }
        
        if (bulkSyncBtn) {
            bulkSyncBtn.addEventListener('click', () => {
                this.bulkSync();
            });
        }

        // Edit annotations button
        const editAnnotationsBtn = document.getElementById('edit-annotations');
        if (editAnnotationsBtn) {
            editAnnotationsBtn.addEventListener('click', () => {
                this.editAnnotations();
            });
        }
    }

    async loadData() {
        try {
            // Show loading with fallback
            if (typeof showLoading === 'function') {
                showLoading('Loading training data...');
            } else {
                console.log('Loading: Loading training data...');
            }
            
            console.log('Getting all coupons from storage...');
            this.allCoupons = await storage.getAllCoupons();
            console.log('Retrieved coupons:', this.allCoupons);
            
            // Load annotations for each coupon
            for (const coupon of this.allCoupons) {
                console.log(`Loading annotations for coupon ${coupon.id}...`);
                coupon.annotations = await storage.getAnnotations(coupon.id);
                console.log(`Found ${coupon.annotations?.length || 0} annotations for ${coupon.id}`);
            }
            
            this.filteredCoupons = [...this.allCoupons];
            
            // Hide loading with fallback
            if (typeof hideLoading === 'function') {
                hideLoading();
            } else {
                console.log('Loading complete');
            }
            
            console.log(`Loaded ${this.allCoupons.length} coupons`);
            
        } catch (error) {
            // Hide loading with fallback
            if (typeof hideLoading === 'function') {
                hideLoading();
            } else {
                console.log('Loading complete (error)');
            }
            console.error('Failed to load data:', error);
            // Show error toast with fallback
            if (typeof showToast === 'function') {
                showToast('Failed to load data: ' + error.message, 'error');
            } else {
                console.error('❌ Failed to load data: ' + error.message);
            }
        }
    }

    updateDisplay() {
        this.updateStats();
        this.renderDataList();
        this.updateControlStates();
    }

    updateStats() {
        const totalImages = this.allCoupons.length;
        const totalAnnotations = this.allCoupons.reduce((sum, coupon) => 
            sum + coupon.annotations.length, 0
        );
        const pendingSync = this.allCoupons.filter(coupon => 
            coupon.status === 'pending_upload'
        ).length;

        const elements = {
            'total-images': totalImages,
            'total-annotations': totalAnnotations,
            'pending-sync': pendingSync
        };

        Object.entries(elements).forEach(([id, value]) => {
            const element = document.getElementById(id);
            if (element) {
                element.textContent = value;
            }
        });
    }

    renderDataList() {
        const dataList = document.getElementById('data-list');
        const emptyState = document.getElementById('empty-state');
        
        if (!dataList) return;

        // Clear existing items
        dataList.innerHTML = '';

        if (this.filteredCoupons.length === 0) {
            if (emptyState) {
                emptyState.classList.remove('hidden');
            }
            return;
        }

        if (emptyState) {
            emptyState.classList.add('hidden');
        }

        // Render items
        this.filteredCoupons.forEach(coupon => {
            const item = this.createDataItem(coupon);
            dataList.appendChild(item);
        });
    }

    createDataItem(coupon) {
        const item = document.createElement('div');
        item.className = 'data-item';
        item.dataset.id = coupon.id;
        
        if (this.selectedItems.has(coupon.id)) {
            item.classList.add('selected');
        }

        const statusBadgeClass = this.getStatusBadgeClass(coupon.status);
        
        // Format date with fallback
        const uploadDate = (window.CouponTrainerApp && window.CouponTrainerApp.formatDate) 
            ? window.CouponTrainerApp.formatDate(coupon.uploadDate)
            : new Date(coupon.uploadDate).toLocaleDateString();
            
        // Format file size with fallback
        const fileSize = (window.CouponTrainerApp && window.CouponTrainerApp.formatFileSize)
            ? window.CouponTrainerApp.formatFileSize(coupon.metadata?.size || 0)
            : this.formatFileSize(coupon.metadata?.size || 0);
        
        item.innerHTML = `
            <input type="checkbox" class="data-checkbox" ${this.selectedItems.has(coupon.id) ? 'checked' : ''}>
            <img src="${coupon.imageData}" alt="${coupon.filename}" class="data-thumbnail" loading="lazy">
            <div class="data-info">
                <div class="data-title">${coupon.filename}</div>
                <div class="data-meta">
                    <span>📅 ${uploadDate}</span>
                    <span>📏 ${fileSize}</span>
                    <span>🏷️ ${coupon.annotations.length} annotations</span>
                </div>
            </div>
            <div class="data-status">
                <span class="status-badge ${statusBadgeClass}">${coupon.status.replace('_', ' ')}</span>
            </div>
        `;

        // Event listeners
        const checkbox = item.querySelector('.data-checkbox');
        checkbox.addEventListener('change', (e) => {
            e.stopPropagation();
            this.toggleItemSelection(coupon.id);
        });

        item.addEventListener('click', (e) => {
            if (e.target.type !== 'checkbox') {
                this.showPreview(coupon);
            }
        });

        return item;
    }

    getStatusBadgeClass(status) {
        const classes = {
            'uploaded': 'pending',
            'annotated': 'annotated',
            'pending_upload': 'pending'
        };
        return classes[status] || 'pending';
    }
    
    formatFileSize(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    toggleFilterPanel() {
        const filterPanel = document.getElementById('filter-panel');
        if (filterPanel) {
            filterPanel.classList.toggle('hidden');
        }
    }

    applyFilters() {
        const fieldFilter = document.getElementById('field-filter');
        const statusFilter = document.getElementById('status-filter');
        
        this.currentFilters = {
            fieldType: fieldFilter ? fieldFilter.value : '',
            status: statusFilter ? statusFilter.value : ''
        };

        this.filteredCoupons = this.allCoupons.filter(coupon => {
            // Field type filter
            if (this.currentFilters.fieldType) {
                const hasFieldType = coupon.annotations.some(annotation => 
                    annotation.fieldType === this.currentFilters.fieldType
                );
                if (!hasFieldType) return false;
            }

            // Status filter
            if (this.currentFilters.status && coupon.status !== this.currentFilters.status) {
                return false;
            }

            return true;
        });

        this.updateDisplay();
        this.toggleFilterPanel();
        
        const activeFilters = Object.values(this.currentFilters).filter(v => v).length;
        showToast(`${activeFilters} filter(s) applied. Showing ${this.filteredCoupons.length} items.`, 'info');
    }

    clearFilters() {
        this.currentFilters = {
            fieldType: '',
            status: ''
        };

        // Reset filter controls
        const fieldFilter = document.getElementById('field-filter');
        const statusFilter = document.getElementById('status-filter');
        
        if (fieldFilter) fieldFilter.value = '';
        if (statusFilter) statusFilter.value = '';

        this.filteredCoupons = [...this.allCoupons];
        this.updateDisplay();
        this.toggleFilterPanel();
        
        showToast('Filters cleared', 'info');
    }

    toggleItemSelection(couponId) {
        if (this.selectedItems.has(couponId)) {
            this.selectedItems.delete(couponId);
        } else {
            this.selectedItems.add(couponId);
        }

        this.updateItemDisplay(couponId);
        this.updateControlStates();
    }

    toggleSelectAll() {
        const allSelected = this.selectedItems.size === this.filteredCoupons.length;
        
        if (allSelected) {
            this.selectedItems.clear();
        } else {
            this.filteredCoupons.forEach(coupon => {
                this.selectedItems.add(coupon.id);
            });
        }

        this.renderDataList();
        this.updateControlStates();
    }

    updateItemDisplay(couponId) {
        const item = document.querySelector(`[data-id="${couponId}"]`);
        if (!item) return;

        const checkbox = item.querySelector('.data-checkbox');
        const isSelected = this.selectedItems.has(couponId);
        
        checkbox.checked = isSelected;
        item.classList.toggle('selected', isSelected);
    }

    updateControlStates() {
        const selectedCount = this.selectedItems.size;
        const deleteBtn = document.getElementById('delete-selected');
        const exportBtn = document.getElementById('export-selected');
        const selectAllBtn = document.getElementById('select-all');

        if (deleteBtn) {
            deleteBtn.disabled = selectedCount === 0;
        }
        
        if (exportBtn) {
            exportBtn.disabled = selectedCount === 0;
        }
        
        if (selectAllBtn) {
            const allSelected = selectedCount === this.filteredCoupons.length && this.filteredCoupons.length > 0;
            selectAllBtn.textContent = allSelected ? 'Deselect All' : 'Select All';
        }
    }

    async deleteSelected() {
        if (this.selectedItems.size === 0) return;

        const confirmed = confirm(`Delete ${this.selectedItems.size} selected items? This cannot be undone.`);
        if (!confirmed) return;

        showLoading('Deleting items...');

        try {
            const deletePromises = Array.from(this.selectedItems).map(id => 
                storage.deleteCoupon(id)
            );
            
            await Promise.all(deletePromises);
            
            // Remove from local arrays
            this.allCoupons = this.allCoupons.filter(coupon => 
                !this.selectedItems.has(coupon.id)
            );
            this.filteredCoupons = this.filteredCoupons.filter(coupon => 
                !this.selectedItems.has(coupon.id)
            );
            
            const deletedCount = this.selectedItems.size;
            this.selectedItems.clear();
            
            hideLoading();
            this.updateDisplay();
            
            showToast(`${deletedCount} items deleted`, 'success');
            
        } catch (error) {
            hideLoading();
            console.error('Failed to delete items:', error);
            showToast('Failed to delete items: ' + error.message, 'error');
        }
    }

    async exportSelected() {
        if (this.selectedItems.size === 0) return;

        showLoading('Preparing export...');

        try {
            const selectedCoupons = this.allCoupons.filter(coupon => 
                this.selectedItems.has(coupon.id)
            );

            const exportData = {
                version: '1.0.0',
                exportDate: new Date().toISOString(),
                itemCount: selectedCoupons.length,
                coupons: selectedCoupons.map(coupon => ({
                    filename: coupon.filename,
                    uploadDate: coupon.uploadDate,
                    annotations: coupon.annotations,
                    status: coupon.status,
                    imageData: coupon.imageData // Include image data for complete export
                }))
            };

            const blob = new Blob([JSON.stringify(exportData, null, 2)], {
                type: 'application/json'
            });

            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `coupon-data-export-${new Date().toISOString().split('T')[0]}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);

            hideLoading();
            showToast(`Exported ${selectedCoupons.length} items`, 'success');

        } catch (error) {
            hideLoading();
            console.error('Export failed:', error);
            showToast('Export failed: ' + error.message, 'error');
        }
    }

    showPreview(coupon) {
        const modal = document.getElementById('preview-modal');
        const canvas = document.getElementById('preview-canvas');
        const annotationInfo = document.getElementById('annotation-info');
        const modalTitle = document.getElementById('modal-title');
        
        if (!modal || !canvas) return;

        // Set modal title
        if (modalTitle) {
            modalTitle.textContent = coupon.filename;
        }

        // Load image and annotations on canvas
        const ctx = canvas.getContext('2d');
        const img = new Image();
        
        img.onload = () => {
            // Set canvas size
            const maxWidth = 500;
            const maxHeight = 400;
            const ratio = Math.min(maxWidth / img.width, maxHeight / img.height);
            
            canvas.width = img.width * ratio;
            canvas.height = img.height * ratio;
            
            // Draw image
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
            
            // Draw annotations
            this.drawAnnotationsOnCanvas(ctx, coupon.annotations, canvas.width, canvas.height);
        };
        
        img.src = coupon.imageData;

        // Update annotation info
        if (annotationInfo) {
            this.updateAnnotationInfo(annotationInfo, coupon.annotations);
        }

        // Store current coupon for editing
        this.currentPreviewCoupon = coupon;
        
        // Show modal
        modal.classList.remove('hidden');
    }

    drawAnnotationsOnCanvas(ctx, annotations, canvasWidth, canvasHeight) {
        const fieldColors = {
            'code_region': '#ff6b6b',
            'benefit_region': '#4ecdc4',
            'expiry_region': '#45b7d1',
            'app_region': '#f9ca24',
            'terms_region': '#a55eea'
        };

        annotations.forEach(annotation => {
            const bbox = annotation.boundingBox;
            const color = fieldColors[annotation.fieldType] || '#666666';
            
            const x = bbox.x * canvasWidth;
            const y = bbox.y * canvasHeight;
            const width = bbox.width * canvasWidth;
            const height = bbox.height * canvasHeight;
            
            // Draw rectangle
            ctx.strokeStyle = color;
            ctx.lineWidth = 2;
            ctx.strokeRect(x, y, width, height);
            
            // Draw label
            ctx.fillStyle = color;
            ctx.font = 'bold 12px Arial';
            const label = this.getFieldDisplayName(annotation.fieldType);
            
            ctx.fillRect(x, y - 20, ctx.measureText(label).width + 8, 18);
            ctx.fillStyle = 'white';
            ctx.fillText(label, x + 4, y - 6);
        });
    }

    updateAnnotationInfo(container, annotations) {
        const fieldCounts = {};
        annotations.forEach(annotation => {
            fieldCounts[annotation.fieldType] = (fieldCounts[annotation.fieldType] || 0) + 1;
        });

        const infoHTML = `
            <h4>Annotations (${annotations.length})</h4>
            <div class="annotation-summary">
                ${Object.entries(fieldCounts).map(([fieldType, count]) => `
                    <div class="field-summary">
                        <span class="field-name">${this.getFieldDisplayName(fieldType)}:</span>
                        <span class="field-count">${count}</span>
                    </div>
                `).join('')}
            </div>
        `;

        container.innerHTML = infoHTML;
    }

    getFieldDisplayName(fieldType) {
        const displayNames = {
            'code_region': 'Coupon Code',
            'benefit_region': 'Benefit',
            'expiry_region': 'Expiry Date',
            'app_region': 'App Name',
            'terms_region': 'Terms'
        };
        return displayNames[fieldType] || fieldType;
    }

    closeModal(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.add('hidden');
        }
    }

    editAnnotations() {
        if (!this.currentPreviewCoupon) return;
        
        // Navigate to upload page with edit mode
        const url = `/upload.html?edit=${this.currentPreviewCoupon.id}`;
        window.location.href = url;
    }

    bulkExport() {
        this.exportSelected();
        this.closeModal('bulk-modal');
    }

    bulkDelete() {
        this.deleteSelected();
        this.closeModal('bulk-modal');
    }

    async bulkSync() {
        if (this.selectedItems.size === 0) return;

        if (!navigator.onLine) {
            showToast('Cannot sync while offline', 'warning');
            return;
        }

        showLoading('Syncing data...');

        try {
            // Simulate sync process
            await new Promise(resolve => setTimeout(resolve, 2000));
            
            // Update status of selected items
            const selectedCoupons = this.allCoupons.filter(coupon => 
                this.selectedItems.has(coupon.id)
            );
            
            for (const coupon of selectedCoupons) {
                await storage.updateCouponStatus(coupon.id, 'uploaded');
                coupon.status = 'uploaded';
            }

            hideLoading();
            this.updateDisplay();
            this.closeModal('bulk-modal');
            
            showToast(`${selectedCoupons.length} items synced`, 'success');

        } catch (error) {
            hideLoading();
            console.error('Sync failed:', error);
            showToast('Sync failed: ' + error.message, 'error');
        }
    }
}

// Initialize data manager when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('data-list')) {
        window.dataManager = new DataManager();
    }
});

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = DataManager;
} else {
    window.DataManager = DataManager;
}

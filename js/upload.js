// Upload functionality for Coupon Trainer PWA

class UploadManager {
    constructor() {
        this.uploadArea = null;
        this.fileInput = null;
        this.uploadedImages = [];
        this.currentStep = 'upload'; // upload, preview, annotate, results
        
        this.init();
    }

    init() {
        console.log('Initializing Upload Manager...');
        
        this.setupElements();
        this.setupEventListeners();
        this.setupDragAndDrop();
        
        console.log('Upload Manager initialized');
    }

    setupElements() {
        this.uploadArea = document.getElementById('upload-area');
        this.fileInput = document.getElementById('file-input');
        this.uploadButton = document.getElementById('upload-button');
        this.uploadProgress = document.getElementById('upload-progress');
        this.progressFill = document.getElementById('progress-fill');
        this.progressText = document.getElementById('progress-text');
        
        this.previewSection = document.getElementById('preview-section');
        this.imageGrid = document.getElementById('image-grid');
        
        this.annotationSection = document.getElementById('annotation-section');
        this.resultsSection = document.getElementById('results-section');
    }

    setupEventListeners() {
        // File input change
        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => {
                this.handleFiles(e.target.files);
            });
        }

        // Upload button click
        if (this.uploadButton) {
            this.uploadButton.addEventListener('click', () => {
                this.fileInput.click();
            });
        }

        // Upload another button in results
        const uploadAnotherBtn = document.getElementById('upload-another');
        if (uploadAnotherBtn) {
            uploadAnotherBtn.addEventListener('click', () => {
                this.resetUpload();
            });
        }
    }

    setupDragAndDrop() {
        if (!this.uploadArea) return;

        // Prevent default drag behaviors
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            this.uploadArea.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
            });
        });

        // Highlight drop area when item is dragged over it
        ['dragenter', 'dragover'].forEach(eventName => {
            this.uploadArea.addEventListener(eventName, () => {
                this.uploadArea.classList.add('dragover');
            });
        });

        ['dragleave', 'drop'].forEach(eventName => {
            this.uploadArea.addEventListener(eventName, () => {
                this.uploadArea.classList.remove('dragover');
            });
        });

        // Handle dropped files
        this.uploadArea.addEventListener('drop', (e) => {
            const files = e.dataTransfer.files;
            this.handleFiles(files);
        });
    }

    async handleFiles(files) {
        if (!files || files.length === 0) return;

        console.log(`Processing ${files.length} files...`);
        
        // Filter for image files only
        const imageFiles = Array.from(files).filter(file => 
            file.type.startsWith('image/')
        );

        if (imageFiles.length === 0) {
            showToast('Please select valid image files', 'warning');
            return;
        }

        if (imageFiles.length !== files.length) {
            showToast(`${files.length - imageFiles.length} non-image files were skipped`, 'info');
        }

        this.showProgress();
        
        try {
            await this.processImages(imageFiles);
            this.showPreview();
        } catch (error) {
            console.error('Failed to process images:', error);
            showToast('Failed to process images: ' + error.message, 'error');
            this.hideProgress();
        }
    }

    async processImages(files) {
        this.uploadedImages = [];
        
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const progress = Math.round(((i + 1) / files.length) * 100);
            
            this.updateProgress(progress, `Processing ${file.name}...`);
            
            try {
                // Convert to base64
                const imageData = await StorageUtils.fileToBase64(file);
                
                // Create thumbnail
                const thumbnail = await StorageUtils.createThumbnail(imageData);
                
                // Generate unique filename
                const uniqueFilename = StorageUtils.generateFilename(file.name);
                
                const imageInfo = {
                    originalFile: file,
                    filename: uniqueFilename,
                    originalName: file.name,
                    data: imageData,
                    thumbnail: thumbnail,
                    size: file.size,
                    type: file.type,
                    uploadTime: new Date().toISOString(),
                    annotations: [],
                    status: 'uploaded'
                };
                
                this.uploadedImages.push(imageInfo);
                
                // Small delay to show progress
                await new Promise(resolve => setTimeout(resolve, 100));
                
            } catch (error) {
                console.error('Failed to process file:', file.name, error);
                showToast(`Failed to process ${file.name}`, 'error');
            }
        }
        
        this.updateProgress(100, 'Processing complete!');
        
        // Hide progress after a short delay
        setTimeout(() => {
            this.hideProgress();
        }, 1000);
    }

    showProgress() {
        if (this.uploadProgress) {
            this.uploadProgress.classList.remove('hidden');
        }
        this.updateProgress(0, 'Starting upload...');
    }

    hideProgress() {
        if (this.uploadProgress) {
            this.uploadProgress.classList.add('hidden');
        }
    }

    updateProgress(percentage, text) {
        if (this.progressFill) {
            this.progressFill.style.width = `${percentage}%`;
        }
        
        if (this.progressText) {
            this.progressText.textContent = `${text} ${percentage}%`;
        }
    }

    showPreview() {
        if (!this.previewSection || !this.imageGrid) return;

        this.currentStep = 'preview';
        this.previewSection.classList.remove('hidden');
        
        // Clear existing preview
        this.imageGrid.innerHTML = '';
        
        // Add images to grid
        this.uploadedImages.forEach((image, index) => {
            const imageItem = this.createImagePreview(image, index);
            this.imageGrid.appendChild(imageItem);
        });

        // Scroll to preview section
        this.previewSection.scrollIntoView({ behavior: 'smooth' });
        
        showToast(`${this.uploadedImages.length} images ready for annotation`, 'success');
        
        // Auto-proceed to annotation after a short delay
        setTimeout(() => {
            this.startAnnotation();
        }, 2000);
    }

    createImagePreview(image, index) {
        const item = document.createElement('div');
        item.className = 'image-item';
        item.dataset.index = index;
        
        item.innerHTML = `
            <img src="${image.thumbnail}" alt="${image.originalName}" loading="lazy">
            <div class="image-overlay">
                <span>Click to annotate</span>
            </div>
            <div class="image-status ${image.status}"></div>
        `;
        
        item.addEventListener('click', () => {
            this.startAnnotationForImage(index);
        });
        
        return item;
    }

    startAnnotation() {
        if (this.uploadedImages.length === 0) return;

        this.currentStep = 'annotate';
        
        if (this.annotationSection) {
            this.annotationSection.classList.remove('hidden');
            this.annotationSection.scrollIntoView({ behavior: 'smooth' });
        }

        // Initialize annotation manager with uploaded images
        if (window.annotationManager) {
            const imageFiles = this.uploadedImages.map(img => ({
                name: img.originalName,
                size: img.size,
                type: img.type
            }));
            
            // Convert to File objects for annotation manager
            const files = this.uploadedImages.map(img => img.originalFile);
            
            window.annotationManager.addImages(files).then(() => {
                showToast('Ready to annotate! Select field types and draw rectangles.', 'info');
            });
        }
    }

    startAnnotationForImage(index) {
        this.startAnnotation();
        
        // Load specific image in annotation manager
        if (window.annotationManager) {
            window.annotationManager.loadImage(index);
        }
    }

    async saveAnnotations() {
        console.log('saveAnnotations called');
        
        // Wait for annotation manager to be available
        let retryCount = 0;
        while (!window.annotationManager && retryCount < 10) {
            console.log(`Waiting for annotation manager... (${retryCount + 1}/10)`);
            await new Promise(resolve => setTimeout(resolve, 100));
            retryCount++;
        }
        
        if (!window.annotationManager) {
            console.error('Annotation manager not available after waiting');
            showToast('Annotation system not ready. Please try again.', 'error');
            return;
        }

        console.log('Annotation manager available, starting save...');
        
        // Check if there are any annotations to save
        const summary = window.annotationManager.getAnnotationSummary();
        console.log('Annotation summary:', summary);
        
        if (summary.totalAnnotations === 0) {
            // Show toast with fallback
            if (typeof showToast === 'function') {
                showToast('No annotations to save. Please annotate at least one region first.', 'warning');
            } else {
                console.warn('No annotations to save. Please annotate at least one region first.');
            }
            return;
        }
        
        // Show loading with fallback
        if (typeof showLoading === 'function') {
            showLoading('Saving annotations...');
        } else {
            console.log('Loading: Saving annotations...');
        }
        
        try {
            // Save current annotations first
            window.annotationManager.saveCurrentAnnotations();
            
            // Save all annotations to storage
            console.log('Calling saveAllToStorage...');
            const results = await window.annotationManager.saveAllToStorage();
            console.log('Save results:', results);
            
            // Update upload status
            const successCount = results.filter(r => r.success).length;
            const failureCount = results.length - successCount;
            
            // Hide loading with fallback
            if (typeof hideLoading === 'function') {
                hideLoading();
            } else {
                console.log('Loading complete');
            }
            
            if (failureCount === 0) {
                // Show success toast with fallback
                if (typeof showToast === 'function') {
                    showToast(`All ${successCount} images saved successfully!`, 'success');
                } else {
                    console.log(`✅ All ${successCount} images saved successfully!`);
                }
                this.showResults(successCount, window.annotationManager.getAnnotationSummary().totalAnnotations);
            } else {
                // Show warning toast with fallback
                if (typeof showToast === 'function') {
                    showToast(`${successCount} saved, ${failureCount} failed`, 'warning');
                } else {
                    console.warn(`⚠️ ${successCount} saved, ${failureCount} failed`);
                }
                this.showResults(successCount, window.annotationManager.getAnnotationSummary().totalAnnotations);
            }
            
        } catch (error) {
            // Hide loading with fallback
            if (typeof hideLoading === 'function') {
                hideLoading();
            } else {
                console.log('Loading complete (error)');
            }
            // Show error toast with fallback
            if (typeof showToast === 'function') {
                showToast('Failed to save annotations: ' + error.message, 'error');
            } else {
                console.error('❌ Failed to save annotations: ' + error.message);
            }
            console.error('Save failed:', error);
        }
    }

    showResults(processedCount, annotationCount) {
        this.currentStep = 'results';
        
        if (this.resultsSection) {
            this.resultsSection.classList.remove('hidden');
            this.resultsSection.scrollIntoView({ behavior: 'smooth' });
        }

        // Update result counts
        const processedElement = document.getElementById('processed-count');
        const annotationCountElement = document.getElementById('annotation-count');
        const saveStatusElement = document.getElementById('save-status');
        
        if (processedElement) {
            processedElement.textContent = processedCount;
        }
        
        if (annotationCountElement) {
            annotationCountElement.textContent = annotationCount;
        }
        
        if (saveStatusElement) {
            saveStatusElement.textContent = navigator.onLine ? 'Saved Locally' : 'Saved Offline';
        }
    }

    resetUpload() {
        // Reset state
        this.uploadedImages = [];
        this.currentStep = 'upload';
        
        // Hide all sections except upload
        const sections = [this.previewSection, this.annotationSection, this.resultsSection];
        sections.forEach(section => {
            if (section) {
                section.classList.add('hidden');
            }
        });
        
        // Clear file input
        if (this.fileInput) {
            this.fileInput.value = '';
        }
        
        // Clear image grid
        if (this.imageGrid) {
            this.imageGrid.innerHTML = '';
        }
        
        // Reset annotation manager
        if (window.annotationManager) {
            window.annotationManager.images = [];
            window.annotationManager.currentImageIndex = 0;
        }
        
        // Scroll to top
        window.scrollTo({ top: 0, behavior: 'smooth' });
        
        showToast('Ready for new upload', 'info');
    }

    // Public method to be called from annotation.js
    handleAnnotationSave() {
        this.saveAnnotations();
    }

    getUploadSummary() {
        return {
            totalImages: this.uploadedImages.length,
            totalSize: this.uploadedImages.reduce((sum, img) => sum + img.size, 0),
            status: this.currentStep
        };
    }
}

// Initialize upload manager when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('upload-area')) {
        window.uploadManager = new UploadManager();
        
        // Connect save button to upload manager
        const saveBtn = document.getElementById('save-annotations');
        if (saveBtn) {
            console.log('Save button found, adding event listener');
            saveBtn.addEventListener('click', () => {
                console.log('Save button clicked!');
                if (window.uploadManager) {
                    console.log('Upload manager available, calling handleAnnotationSave');
                    window.uploadManager.handleAnnotationSave();
                } else {
                    console.error('Upload manager not available');
                }
            });
        } else {
            console.error('Save button not found!');
        }

        // Setup collapsible field types
        setupCollapsible();
    }
});

// Collapsible functionality
function setupCollapsible() {
    const header = document.getElementById('field-types-header');
    const content = document.getElementById('field-types-content');
    
    if (header && content) {
        header.addEventListener('click', () => {
            const isCollapsed = content.classList.contains('collapsed');
            
            if (isCollapsed) {
                // Expand
                content.classList.remove('collapsed');
                header.classList.remove('collapsed');
                console.log('Field types expanded');
            } else {
                // Collapse
                content.classList.add('collapsed');
                header.classList.add('collapsed');
                console.log('Field types collapsed');
            }
        });
        
        console.log('Collapsible field types setup complete');
    } else {
        console.warn('Collapsible elements not found');
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = UploadManager;
} else {
    window.UploadManager = UploadManager;
}

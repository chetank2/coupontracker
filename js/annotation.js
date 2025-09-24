// Touch-based Annotation System for Coupon Trainer PWA

class AnnotationCanvas {
    constructor(canvasId, imageId) {
        this.canvas = document.getElementById(canvasId);
        this.ctx = this.canvas.getContext('2d');
        this.image = document.getElementById(imageId);
        
        this.annotations = [];
        this.currentAnnotation = null;
        this.isDrawing = false;
        this.startPoint = null;
        this.currentFieldType = 'code_region';
        
        // Field colors
        this.fieldColors = {
            'code_region': '#ff6b6b',
            'benefit_region': '#4ecdc4', 
            'expiry_region': '#45b7d1',
            'app_region': '#f9ca24',
            'terms_region': '#a55eea'
        };
        
        this.setupEventListeners();
        this.setupTouchHandling();
        this.setupResizeHandler();
    }

    setupEventListeners() {
        // Mouse events for desktop testing
        this.canvas.addEventListener('mousedown', (e) => this.startDrawing(e));
        this.canvas.addEventListener('mousemove', (e) => this.draw(e));
        this.canvas.addEventListener('mouseup', (e) => this.stopDrawing(e));
        this.canvas.addEventListener('mouseout', (e) => this.stopDrawing(e));
        
        // Prevent context menu on right click
        this.canvas.addEventListener('contextmenu', (e) => e.preventDefault());
    }

    setupTouchHandling() {
        // Enhanced touch handling for better precision
        let touchStartTime = 0;
        let touchMoved = false;
        let longPressTimer = null;
        
        this.canvas.addEventListener('touchstart', (e) => {
            // Only handle single touch for annotation (avoid conflicts with zoom gestures)
            if (e.touches.length !== 1) {
                console.log(`Ignoring ${e.touches.length}-finger touch for annotation`);
                return;
            }
            
            e.preventDefault();
            e.stopPropagation(); // Prevent zoom handler from seeing this
            
            touchStartTime = Date.now();
            touchMoved = false;
            
            const touch = e.touches[0];
            
            console.log('Starting annotation touch at:', touch.clientX, touch.clientY);
            
            // Show touch indicator for better feedback
            this.showTouchIndicator(touch.clientX, touch.clientY);
            
            // Start long press timer for precision mode
            longPressTimer = setTimeout(() => {
                this.enterPrecisionMode(touch);
            }, 300);
            
            const mouseEvent = new MouseEvent('mousedown', {
                clientX: touch.clientX,
                clientY: touch.clientY
            });
            this.canvas.dispatchEvent(mouseEvent);
        });

        this.canvas.addEventListener('touchmove', (e) => {
            // Only handle single touch for annotation
            if (e.touches.length !== 1) return;
            
            e.preventDefault();
            e.stopPropagation(); // Prevent zoom handler from seeing this
            
            touchMoved = true;
            
            // Clear long press timer if user moves
            if (longPressTimer) {
                clearTimeout(longPressTimer);
                longPressTimer = null;
            }
            
            const touch = e.touches[0];
            
            console.log('Moving annotation touch to:', touch.clientX, touch.clientY);
            
            // Update touch indicator
            this.updateTouchIndicator(touch.clientX, touch.clientY);
            
            const mouseEvent = new MouseEvent('mousemove', {
                clientX: touch.clientX,
                clientY: touch.clientY
            });
            this.canvas.dispatchEvent(mouseEvent);
        });

        this.canvas.addEventListener('touchend', (e) => {
            e.preventDefault();
            e.stopPropagation(); // Prevent zoom handler from seeing this
            
            console.log('Ending annotation touch');
            
            // Clear long press timer
            if (longPressTimer) {
                clearTimeout(longPressTimer);
                longPressTimer = null;
            }
            
            // Hide touch indicator
            this.hideTouchIndicator();
            
            // Exit precision mode
            this.exitPrecisionMode();
            
            const touchDuration = Date.now() - touchStartTime;
            
            // Handle tap vs drag
            if (!touchMoved && touchDuration < 200) {
                // Quick tap - could be used for point annotations in future
                console.log('Quick tap detected');
            }
            
            const mouseEvent = new MouseEvent('mouseup', {});
            this.canvas.dispatchEvent(mouseEvent);
        });

        // Prevent default touch behaviors - we'll handle everything
        this.canvas.style.touchAction = 'none';
    }
    
    setupResizeHandler() {
        // Handle window resize and orientation changes
        let resizeTimeout;
        const handleResize = () => {
            clearTimeout(resizeTimeout);
            resizeTimeout = setTimeout(() => {
                console.log('Window resized, updating canvas size');
                this.updateCanvasSize();
                this.redrawCanvas();
            }, 250);
        };
        
        window.addEventListener('resize', handleResize);
        window.addEventListener('orientationchange', handleResize);
    }
    
    showTouchIndicator(x, y) {
        // Create or update touch indicator
        let indicator = document.getElementById('touch-indicator');
        if (!indicator) {
            indicator = document.createElement('div');
            indicator.id = 'touch-indicator';
            indicator.style.cssText = `
                position: fixed;
                width: 20px;
                height: 20px;
                border: 2px solid #007bff;
                border-radius: 50%;
                background: rgba(0, 123, 255, 0.2);
                pointer-events: none;
                z-index: 1000;
                transform: translate(-50%, -50%);
                transition: all 0.1s ease;
            `;
            document.body.appendChild(indicator);
        }
        
        indicator.style.left = x + 'px';
        indicator.style.top = y + 'px';
        indicator.style.display = 'block';
    }
    
    updateTouchIndicator(x, y) {
        const indicator = document.getElementById('touch-indicator');
        if (indicator) {
            indicator.style.left = x + 'px';
            indicator.style.top = y + 'px';
        }
    }
    
    hideTouchIndicator() {
        const indicator = document.getElementById('touch-indicator');
        if (indicator) {
            indicator.style.display = 'none';
        }
    }
    
    enterPrecisionMode(touch) {
        // Visual feedback for precision mode
        const indicator = document.getElementById('touch-indicator');
        if (indicator) {
            indicator.style.width = '30px';
            indicator.style.height = '30px';
            indicator.style.borderColor = '#28a745';
            indicator.style.background = 'rgba(40, 167, 69, 0.3)';
        }
        
        // Could add magnifying glass or crosshair here
        console.log('Precision mode activated');
    }
    
    exitPrecisionMode() {
        const indicator = document.getElementById('touch-indicator');
        if (indicator) {
            indicator.style.width = '20px';
            indicator.style.height = '20px';
            indicator.style.borderColor = '#007bff';
            indicator.style.background = 'rgba(0, 123, 255, 0.2)';
        }
    }

    getCanvasCoordinates(event) {
        const rect = this.canvas.getBoundingClientRect();
        let scaleX = this.canvas.width / rect.width;
        let scaleY = this.canvas.height / rect.height;
        
        // Get zoom information if mobile zoom handler is available
        let zoomScale = 1;
        let panOffset = { x: 0, y: 0 };
        
        if (window.mobileZoomHandler) {
            const zoomInfo = window.mobileZoomHandler.getZoomInfo();
            zoomScale = zoomInfo.scale;
            panOffset = zoomInfo.panOffset;
        }
        
        // Adjust coordinates for zoom and pan
        let x = (event.clientX - rect.left) * scaleX;
        let y = (event.clientY - rect.top) * scaleY;
        
        // Account for zoom and pan
        if (zoomScale !== 1) {
            x = (x / zoomScale) - (panOffset.x / zoomScale);
            y = (y / zoomScale) - (panOffset.y / zoomScale);
        }
        
        return { x, y };
    }

    startDrawing(event) {
        this.isDrawing = true;
        this.startPoint = this.getCanvasCoordinates(event);
        
        this.currentAnnotation = {
            id: Date.now() + Math.random(),
            fieldType: this.currentFieldType,
            startX: this.startPoint.x,
            startY: this.startPoint.y,
            endX: this.startPoint.x,
            endY: this.startPoint.y,
            color: this.fieldColors[this.currentFieldType],
            confidence: 1.0,
            createdAt: new Date().toISOString()
        };
    }

    draw(event) {
        if (!this.isDrawing || !this.currentAnnotation) return;

        const currentPoint = this.getCanvasCoordinates(event);
        this.currentAnnotation.endX = currentPoint.x;
        this.currentAnnotation.endY = currentPoint.y;

        this.redrawCanvas();
    }

    stopDrawing(event) {
        if (!this.isDrawing || !this.currentAnnotation) return;

        this.isDrawing = false;
        
        // Calculate final dimensions
        const width = Math.abs(this.currentAnnotation.endX - this.currentAnnotation.startX);
        const height = Math.abs(this.currentAnnotation.endY - this.currentAnnotation.startY);
        
        // Only save if annotation is large enough (minimum 10x10 pixels)
        if (width > 10 && height > 10) {
            // Normalize coordinates (ensure start is top-left)
            const annotation = {
                ...this.currentAnnotation,
                startX: Math.min(this.currentAnnotation.startX, this.currentAnnotation.endX),
                startY: Math.min(this.currentAnnotation.startY, this.currentAnnotation.endY),
                endX: Math.max(this.currentAnnotation.startX, this.currentAnnotation.endX),
                endY: Math.max(this.currentAnnotation.startY, this.currentAnnotation.endY),
                width: width,
                height: height
            };
            
            this.annotations.push(annotation);
            this.showToast(`${this.getFieldDisplayName(annotation.fieldType)} annotation added`, 'success');
        }
        
        this.currentAnnotation = null;
        this.redrawCanvas();
    }

    redrawCanvas() {
        // Ensure canvas is properly sized
        this.updateCanvasSize();
        
        // Clear canvas
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        
        console.log(`Redrawing canvas: ${this.canvas.width}x${this.canvas.height}`);
        
        // Draw image if loaded
        if (this.image.complete && this.image.naturalWidth > 0) {
            console.log(`Drawing image: ${this.image.naturalWidth}x${this.image.naturalHeight}`);
            this.ctx.drawImage(this.image, 0, 0, this.canvas.width, this.canvas.height);
        }
        
        // Draw existing annotations
        this.annotations.forEach(annotation => {
            this.drawAnnotation(annotation);
        });
        
        // Draw current annotation being drawn
        if (this.currentAnnotation && this.isDrawing) {
            console.log('Drawing current annotation:', this.currentAnnotation);
            this.drawAnnotation(this.currentAnnotation, true);
        }
        
        console.log(`Total annotations: ${this.annotations.length}`);
    }
    
        updateCanvasSize() {
            if (!this.image || !this.image.complete || this.image.naturalWidth === 0) return;
            
            // Always use full viewport width
            const viewportWidth = window.innerWidth;
            
            // Calculate height maintaining aspect ratio
            const aspectRatio = this.image.naturalHeight / this.image.naturalWidth;
            const canvasHeight = viewportWidth * aspectRatio;
            
            // Set canvas size to full width with proper aspect ratio
            this.canvas.width = viewportWidth;
            this.canvas.height = canvasHeight;
            
            // Ensure canvas covers the full width
            this.canvas.style.width = viewportWidth + 'px';
            this.canvas.style.height = canvasHeight + 'px';
            
            console.log(`Canvas sized to full width: ${this.canvas.width}x${this.canvas.height}`);
        }

    drawAnnotation(annotation, isTemporary = false) {
        const { startX, startY, endX, endY, color, fieldType } = annotation;
        
        // Calculate dimensions
        const x = Math.min(startX, endX);
        const y = Math.min(startY, endY);
        const width = Math.abs(endX - startX);
        const height = Math.abs(endY - startY);
        
        // Set style
        this.ctx.strokeStyle = color;
        this.ctx.lineWidth = isTemporary ? 2 : 3;
        this.ctx.setLineDash(isTemporary ? [5, 5] : []);
        this.ctx.globalAlpha = isTemporary ? 0.7 : 1.0;
        
        // Draw rectangle
        this.ctx.strokeRect(x, y, width, height);
        
        // Draw filled background with low opacity
        this.ctx.fillStyle = color;
        this.ctx.globalAlpha = isTemporary ? 0.1 : 0.15;
        this.ctx.fillRect(x, y, width, height);
        
        // Draw label
        this.ctx.globalAlpha = 1.0;
        this.ctx.fillStyle = color;
        this.ctx.font = 'bold 14px Arial';
        
        const label = this.getFieldDisplayName(fieldType);
        const labelWidth = this.ctx.measureText(label).width;
        const labelHeight = 20;
        
        // Position label above or inside the box
        let labelX = x;
        let labelY = y > labelHeight ? y - 5 : y + labelHeight;
        
        // Draw label background
        this.ctx.fillRect(labelX, labelY - labelHeight + 5, labelWidth + 10, labelHeight);
        
        // Draw label text
        this.ctx.fillStyle = 'white';
        this.ctx.fillText(label, labelX + 5, labelY - 5);
        
        // Reset drawing state
        this.ctx.setLineDash([]);
        this.ctx.globalAlpha = 1.0;
    }

    setFieldType(fieldType) {
        this.currentFieldType = fieldType;
        console.log('Field type set to:', fieldType);
    }

    clearAnnotations() {
        this.annotations = [];
        this.redrawCanvas();
        this.showToast('All annotations cleared', 'info');
    }

    undoLastAnnotation() {
        if (this.annotations.length > 0) {
            const removed = this.annotations.pop();
            this.redrawCanvas();
            this.showToast(`${this.getFieldDisplayName(removed.fieldType)} annotation removed`, 'info');
        }
    }

    getAnnotations() {
        return this.annotations.map(annotation => ({
            fieldType: annotation.fieldType,
            boundingBox: {
                x: annotation.startX / this.canvas.width,
                y: annotation.startY / this.canvas.height,
                width: annotation.width / this.canvas.width,
                height: annotation.height / this.canvas.height
            },
            confidence: annotation.confidence,
            createdAt: annotation.createdAt
        }));
    }

    loadAnnotations(annotations) {
        this.annotations = annotations.map(annotation => ({
            id: Date.now() + Math.random(),
            fieldType: annotation.fieldType,
            startX: annotation.boundingBox.x * this.canvas.width,
            startY: annotation.boundingBox.y * this.canvas.height,
            endX: (annotation.boundingBox.x + annotation.boundingBox.width) * this.canvas.width,
            endY: (annotation.boundingBox.y + annotation.boundingBox.height) * this.canvas.height,
            width: annotation.boundingBox.width * this.canvas.width,
            height: annotation.boundingBox.height * this.canvas.height,
            color: this.fieldColors[annotation.fieldType],
            confidence: annotation.confidence || 1.0,
            createdAt: annotation.createdAt
        }));
        
        this.redrawCanvas();
    }

    setImage(imageSrc) {
        return new Promise((resolve) => {
            this.image.onload = () => {
                console.log('Image loaded:', this.image.naturalWidth, 'x', this.image.naturalHeight);
                
                // Wait a moment for the image to be displayed, then size canvas
                setTimeout(() => {
                    this.updateCanvasSize();
                    this.redrawCanvas();
                    console.log('Canvas setup complete');
                }, 100);
                
                // For immediate setup, use natural dimensions
                this.canvas.width = this.image.naturalWidth;
                this.canvas.height = this.image.naturalHeight;
                
                this.redrawCanvas();
                resolve();
            };
            
            this.image.src = imageSrc;
        });
    }

    getFieldDisplayName(fieldType) {
        const displayNames = {
            'code_region': 'Code',
            'benefit_region': 'Benefit',
            'expiry_region': 'Expiry',
            'app_region': 'App',
            'terms_region': 'Terms'
        };
        return displayNames[fieldType] || fieldType;
    }

    showToast(message, type = 'info') {
        // Create toast notification
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        
        const container = document.getElementById('toast-container');
        if (container) {
            container.appendChild(toast);
            
            // Auto remove after 3 seconds
            setTimeout(() => {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, 3000);
        }
    }

    // Export annotation data for training
    exportForTraining() {
        return {
            annotations: this.getAnnotations(),
            imageWidth: this.canvas.width,
            imageHeight: this.canvas.height,
            totalAnnotations: this.annotations.length,
            fieldTypes: [...new Set(this.annotations.map(a => a.fieldType))]
        };
    }

    // Get annotation statistics
    getStats() {
        const fieldCounts = {};
        this.annotations.forEach(annotation => {
            fieldCounts[annotation.fieldType] = (fieldCounts[annotation.fieldType] || 0) + 1;
        });

        return {
            total: this.annotations.length,
            fieldCounts: fieldCounts,
            coverage: this.calculateCoverage()
        };
    }

    calculateCoverage() {
        if (this.annotations.length === 0) return 0;
        
        const totalArea = this.canvas.width * this.canvas.height;
        let annotatedArea = 0;
        
        this.annotations.forEach(annotation => {
            annotatedArea += annotation.width * annotation.height;
        });
        
        return Math.round((annotatedArea / totalArea) * 100);
    }
}

// Annotation Manager - handles multiple images and sessions
class AnnotationManager {
    constructor() {
        this.images = [];
        this.currentImageIndex = 0;
        this.annotationCanvas = null;
        this.isInitialized = false;
    }

    init(canvasId = 'annotation-canvas', imageId = 'current-image') {
        this.annotationCanvas = new AnnotationCanvas(canvasId, imageId);
        this.isInitialized = true;
        this.setupUI();
    }
    
    // Method called by mobile zoom handler to update zoom state
    updateZoom(scale, panOffset) {
        // This method is called by the zoom handler to notify about zoom changes
        // The actual coordinate transformation is handled in getCanvasCoordinates
        if (this.annotationCanvas) {
            // Force redraw to ensure annotations are properly positioned
            this.annotationCanvas.redrawAnnotations();
        }
    }

    setupUI() {
        // Field type buttons
        document.querySelectorAll('.field-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.field-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this.annotationCanvas.setFieldType(btn.dataset.field);
            });
        });

        // Action buttons
        const clearBtn = document.getElementById('clear-annotations');
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                if (confirm('Clear all annotations for this image?')) {
                    this.annotationCanvas.clearAnnotations();
                }
            });
        }

        const undoBtn = document.getElementById('undo-annotation');
        if (undoBtn) {
            undoBtn.addEventListener('click', () => {
                this.annotationCanvas.undoLastAnnotation();
            });
        }

        const saveBtn = document.getElementById('save-annotations');
        if (saveBtn) {
            saveBtn.addEventListener('click', () => {
                this.saveCurrentAnnotations();
            });
        }

        // Navigation buttons
        const prevBtn = document.getElementById('prev-image-btn');
        if (prevBtn) {
            prevBtn.addEventListener('click', () => this.previousImage());
        }

        const nextBtn = document.getElementById('next-image-btn');
        if (nextBtn) {
            nextBtn.addEventListener('click', () => this.nextImage());
        }
    }

    addImages(imageFiles) {
        const promises = imageFiles.map(file => {
            return new Promise((resolve) => {
                const reader = new FileReader();
                reader.onload = (e) => {
                    resolve({
                        file: file,
                        name: file.name,
                        data: e.target.result,
                        annotations: [],
                        status: 'pending'
                    });
                };
                reader.readAsDataURL(file);
            });
        });

        return Promise.all(promises).then(images => {
            this.images = images;
            this.currentImageIndex = 0;
            this.updateUI();
            if (images.length > 0) {
                this.loadImage(0);
            }
            return images;
        });
    }

    loadImage(index) {
        if (index < 0 || index >= this.images.length) return;
        
        this.currentImageIndex = index;
        const image = this.images[index];
        
        return this.annotationCanvas.setImage(image.data).then(() => {
            // Load existing annotations if any
            if (image.annotations.length > 0) {
                this.annotationCanvas.loadAnnotations(image.annotations);
            }
            this.updateUI();
        });
    }

    previousImage() {
        if (this.currentImageIndex > 0) {
            this.saveCurrentAnnotations();
            this.loadImage(this.currentImageIndex - 1);
        }
    }

    nextImage() {
        if (this.currentImageIndex < this.images.length - 1) {
            this.saveCurrentAnnotations();
            this.loadImage(this.currentImageIndex + 1);
        }
    }

    updateUI() {
        const counter = document.getElementById('image-counter');
        if (counter && this.images.length > 0) {
            counter.textContent = `${this.currentImageIndex + 1} of ${this.images.length}`;
        }

        const prevBtn = document.getElementById('prev-image-btn');
        const nextBtn = document.getElementById('next-image-btn');
        
        if (prevBtn) {
            prevBtn.disabled = this.currentImageIndex === 0;
        }
        
        if (nextBtn) {
            nextBtn.disabled = this.currentImageIndex === this.images.length - 1;
        }
    }

    saveCurrentAnnotations() {
        if (this.images.length === 0 || !this.annotationCanvas) return;
        
        const currentImage = this.images[this.currentImageIndex];
        currentImage.annotations = this.annotationCanvas.getAnnotations();
        currentImage.status = currentImage.annotations.length > 0 ? 'annotated' : 'pending';
        
        console.log('Annotations saved for:', currentImage.name);
    }

    async saveAllToStorage() {
        const results = [];
        
        for (let i = 0; i < this.images.length; i++) {
            const image = this.images[i];
            
            try {
                // Save image to storage
                const couponId = await storage.saveCoupon(image.data, image.name);
                
                // Save annotations
                for (const annotation of image.annotations) {
                    await storage.saveAnnotation(couponId, annotation);
                }
                
                // Update coupon status
                await storage.updateCouponStatus(couponId, image.status);
                
                results.push({ success: true, name: image.name, id: couponId });
            } catch (error) {
                console.error('Failed to save:', image.name, error);
                results.push({ success: false, name: image.name, error: error.message });
            }
        }
        
        return results;
    }

    getAnnotationSummary() {
        let totalAnnotations = 0;
        let annotatedImages = 0;
        const fieldCounts = {};
        
        this.images.forEach(image => {
            if (image.annotations.length > 0) {
                annotatedImages++;
                totalAnnotations += image.annotations.length;
                
                image.annotations.forEach(annotation => {
                    fieldCounts[annotation.fieldType] = (fieldCounts[annotation.fieldType] || 0) + 1;
                });
            }
        });
        
        return {
            totalImages: this.images.length,
            annotatedImages,
            totalAnnotations,
            fieldCounts,
            completionRate: Math.round((annotatedImages / this.images.length) * 100)
        };
    }

    exportTrainingData() {
        const trainingData = {
            version: '1.0.0',
            exportDate: new Date().toISOString(),
            summary: this.getAnnotationSummary(),
            images: this.images.map(image => ({
                name: image.name,
                annotations: image.annotations,
                status: image.status
            }))
        };
        
        return trainingData;
    }
}

// Global annotation manager
let annotationManager = null;

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('annotation-canvas')) {
        annotationManager = new AnnotationManager();
        annotationManager.init();
        window.annotationManager = annotationManager; // Make globally accessible
    }
});

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { AnnotationCanvas, AnnotationManager };
} else {
    window.AnnotationCanvas = AnnotationCanvas;
    window.AnnotationManager = AnnotationManager;
}

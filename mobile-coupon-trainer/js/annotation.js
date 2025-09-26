// Enhanced Two-Stage Annotation System for Multi-Coupon Trainer PWA

class MultiCouponAnnotationCanvas {
    constructor(canvasId, imageId) {
        this.canvas = document.getElementById(canvasId);
        this.ctx = this.canvas.getContext('2d');
        this.image = document.getElementById(imageId);
        
        // Basic annotation properties
        this.annotations = [];
        this.currentAnnotation = null;
        this.isDrawing = false;
        this.startPoint = null;
        this.currentFieldType = 'coupon_complete';
        
        // Multi-coupon specific properties
        this.annotationStage = 'stage1_coupons'; // 'stage1_coupons' or 'stage2_fields'
        this.couponInstances = new Map(); // instanceId -> {boundary, fields, status}
        this.currentInstanceId = null;
        this.imageClassification = 'unknown'; // 'single', 'multi_grid', 'scrollable'
        
        // Enhanced colors for two-stage annotation
        this.stageColors = {
            stage1_coupons: {
                'coupon_complete': '#00ff00',
                'coupon_partial_top': '#ffaa00', 
                'coupon_partial_bottom': '#ff6600'
            },
            stage2_fields: {
                'code_region': '#ff6b6b',
                'benefit_region': '#4ecdc4',
                'expiry_region': '#45b7d1',
                'app_region': '#f9ca24',
                'terms_region': '#a55eea'
            }
        };
        
        // Current colors based on stage
        this.fieldColors = this.stageColors.stage1_coupons;
        
        this.setupEventListeners();
        this.setupTouchHandling();
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
        // Touch events for mobile
        this.canvas.addEventListener('touchstart', (e) => {
            e.preventDefault();
            const touch = e.touches[0];
            const mouseEvent = new MouseEvent('mousedown', {
                clientX: touch.clientX,
                clientY: touch.clientY
            });
            this.canvas.dispatchEvent(mouseEvent);
        });

        this.canvas.addEventListener('touchmove', (e) => {
            e.preventDefault();
            const touch = e.touches[0];
            const mouseEvent = new MouseEvent('mousemove', {
                clientX: touch.clientX,
                clientY: touch.clientY
            });
            this.canvas.dispatchEvent(mouseEvent);
        });

        this.canvas.addEventListener('touchend', (e) => {
            e.preventDefault();
            const mouseEvent = new MouseEvent('mouseup', {});
            this.canvas.dispatchEvent(mouseEvent);
        });

        // Prevent scrolling while drawing
        this.canvas.style.touchAction = 'none';
    }

    // Stage Management Methods
    switchToStage1() {
        this.annotationStage = 'stage1_coupons';
        this.fieldColors = this.stageColors.stage1_coupons;
        this.currentFieldType = 'coupon_complete';
        this.updateStageUI();
        console.log('Switched to Stage 1: Coupon Boundaries');
    }
    
    switchToStage2(instanceId = null) {
        if (this.couponInstances.size === 0) {
            this.showToast('Please create at least one coupon boundary in Stage 1 first', 'warning');
            return false;
        }
        
        this.annotationStage = 'stage2_fields';
        this.fieldColors = this.stageColors.stage2_fields;
        this.currentFieldType = 'code_region';
        
        // Set or auto-select instance
        if (instanceId && this.couponInstances.has(instanceId)) {
            this.currentInstanceId = instanceId;
        } else if (!this.currentInstanceId && this.couponInstances.size > 0) {
            this.currentInstanceId = Array.from(this.couponInstances.keys())[0];
        }
        
        this.updateStageUI();
        console.log('Switched to Stage 2: Field Detection for instance:', this.currentInstanceId);
        return true;
    }
    
    updateStageUI() {
        const stageIndicator = document.getElementById('stage-indicator');
        if (stageIndicator) {
            stageIndicator.textContent = this.annotationStage === 'stage1_coupons' 
                ? 'Stage 1: Coupon Boundaries' 
                : 'Stage 2: Field Detection';
        }
        
        // Update stage buttons
        const stage1Btn = document.getElementById('stage1-btn');
        const stage2Btn = document.getElementById('stage2-btn');
        
        if (stage1Btn && stage2Btn) {
            if (this.annotationStage === 'stage1_coupons') {
                stage1Btn.classList.add('active');
                stage2Btn.classList.remove('active');
            } else {
                stage1Btn.classList.remove('active');
                stage2Btn.classList.add('active');
            }
        }
        
        // Update field buttons visibility
        this.updateFieldButtons();
    }
    
    updateFieldButtons() {
        // Hide all buttons first
        document.querySelectorAll('.stage1-btn').forEach(btn => btn.style.display = 'none');
        document.querySelectorAll('.stage2-btn').forEach(btn => btn.style.display = 'none');
        
        if (this.annotationStage === 'stage1_coupons') {
            // Show Stage 1 buttons
            document.querySelectorAll('.stage1-btn').forEach(btn => btn.style.display = 'block');
        } else {
            // Show Stage 2 buttons
            document.querySelectorAll('.stage2-btn').forEach(btn => btn.style.display = 'block');
            
            // Update instance selector
            this.updateInstanceSelector();
        }
        
        // Set first visible button as active
        const visibleButtons = document.querySelectorAll(
            this.annotationStage === 'stage1_coupons' ? '.stage1-btn' : '.stage2-btn'
        );
        
        if (visibleButtons.length > 0) {
            document.querySelectorAll('.field-btn').forEach(btn => btn.classList.remove('active'));
            visibleButtons[0].classList.add('active');
            this.currentFieldType = visibleButtons[0].dataset.field;
        }
    }
    
    updateInstanceSelector() {
        const instanceSelector = document.getElementById('instance-selector');
        if (!instanceSelector) return;
        
        if (this.annotationStage === 'stage2_fields' && this.couponInstances.size > 0) {
            instanceSelector.style.display = 'block';
            
            const instanceButtons = document.getElementById('instance-buttons');
            if (instanceButtons) {
                instanceButtons.innerHTML = '';
                
                Array.from(this.couponInstances.entries()).forEach(([instanceId, instance], index) => {
                    const button = document.createElement('button');
                    button.className = `instance-btn ${instanceId === this.currentInstanceId ? 'active' : ''}`;
                    button.dataset.instance = instanceId;
                    button.textContent = `Coupon ${index + 1} (${instance.status.replace('coupon_', '').replace('_', ' ')})`;
                    
                    button.addEventListener('click', () => {
                        this.currentInstanceId = instanceId;
                        document.querySelectorAll('.instance-btn').forEach(btn => btn.classList.remove('active'));
                        button.classList.add('active');
                        this.redrawCanvas();
                        this.showToast(`Selected Coupon ${index + 1}`, 'info');
                    });
                    
                    instanceButtons.appendChild(button);
                });
            }
        } else {
            instanceSelector.style.display = 'none';
        }
    }

    // Auto-detect coupon layout
    autoDetectCouponLayout() {
        console.log('🔍 Auto-detecting coupon layout...');
        
        // Simple heuristics for layout detection
        const aspectRatio = this.canvas.width / this.canvas.height;
        const imageArea = this.canvas.width * this.canvas.height;
        
        let detectedType = 'single';
        let suggestedInstances = 1;
        
        if (aspectRatio < 0.7) {
            // Tall image - likely scrollable list
            detectedType = 'scrollable';
            suggestedInstances = Math.ceil(this.canvas.height / 300);
        } else if (aspectRatio > 1.5) {
            // Wide image - likely multiple coupons side by side
            detectedType = 'multi_grid';
            suggestedInstances = Math.ceil(this.canvas.width / 400);
        } else if (this.annotations.length > 10) {
            // Many annotations - likely multi-coupon
            detectedType = 'multi_grid';
            suggestedInstances = Math.ceil(this.annotations.length / 5);
        }
        
        this.imageClassification = detectedType;
        
        // Show detection results
        const message = `Detected: ${detectedType.replace('_', ' ').toUpperCase()}\nSuggested instances: ${suggestedInstances}\n\nPlease draw boundaries in Stage 1.`;
        this.showToast(message, 'info');
        
        // Update classification display
        const classificationDisplay = document.getElementById('classification-display');
        if (classificationDisplay) {
            classificationDisplay.textContent = `Detected: ${detectedType.replace('_', ' ')}`;
        }
        
        return { type: detectedType, count: suggestedInstances };
    }

    getCanvasCoordinates(event) {
        const rect = this.canvas.getBoundingClientRect();
        const scaleX = this.canvas.width / rect.width;
        const scaleY = this.canvas.height / rect.height;
        
        return {
            x: (event.clientX - rect.left) * scaleX,
            y: (event.clientY - rect.top) * scaleY
        };
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
            createdAt: new Date().toISOString(),
            annotationStage: this.annotationStage,
            instanceId: this.currentInstanceId
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
            const boundingBox = {
                x: Math.min(this.currentAnnotation.startX, this.currentAnnotation.endX) / this.canvas.width,
                y: Math.min(this.currentAnnotation.startY, this.currentAnnotation.endY) / this.canvas.height,
                width: width / this.canvas.width,
                height: height / this.canvas.height
            };
            
            if (this.annotationStage === 'stage1_coupons') {
                // Create coupon instance
                const instanceId = this.createCouponInstance(boundingBox, this.currentFieldType);
                this.showToast(`Coupon boundary created: ${instanceId}`, 'success');
            } else {
                // Create field annotation
                if (!this.currentInstanceId) {
                    this.showToast('Please select a coupon instance first', 'warning');
                    this.currentAnnotation = null;
                    this.redrawCanvas();
                    return;
                }
                
                // Normalize coordinates (ensure start is top-left)
                const annotation = {
                    ...this.currentAnnotation,
                    startX: Math.min(this.currentAnnotation.startX, this.currentAnnotation.endX),
                    startY: Math.min(this.currentAnnotation.startY, this.currentAnnotation.endY),
                    endX: Math.max(this.currentAnnotation.startX, this.currentAnnotation.endX),
                    endY: Math.max(this.currentAnnotation.startY, this.currentAnnotation.endY),
                    width: width,
                    height: height,
                    boundingBox: boundingBox
                };
                
                this.annotations.push(annotation);
                this.addFieldToInstance(annotation);
                this.showToast(`${this.getFieldDisplayName(annotation.fieldType)} annotation added`, 'success');
            }
        }
        
        this.currentAnnotation = null;
        this.redrawCanvas();
    }

    // Coupon Instance Management
    createCouponInstance(boundingBox, status) {
        const instanceId = StorageUtils.generateInstanceId();
        
        const instance = {
            boundary: boundingBox,
            status: status,
            fields: [],
            createdAt: new Date().toISOString()
        };
        
        this.couponInstances.set(instanceId, instance);
        this.redrawCanvas();
        
        // Update UI if in stage 2
        if (this.annotationStage === 'stage2_fields') {
            this.updateInstanceSelector();
        }
        
        return instanceId;
    }
    
    addFieldToInstance(fieldAnnotation) {
        if (this.currentInstanceId && this.couponInstances.has(this.currentInstanceId)) {
            const instance = this.couponInstances.get(this.currentInstanceId);
            instance.fields.push({
                fieldType: fieldAnnotation.fieldType,
                boundingBox: fieldAnnotation.boundingBox,
                confidence: fieldAnnotation.confidence,
                createdAt: fieldAnnotation.createdAt
            });
        }
    }
    
    deleteCouponInstance(instanceId) {
        if (this.couponInstances.has(instanceId)) {
            this.couponInstances.delete(instanceId);
            
            // Remove associated field annotations
            this.annotations = this.annotations.filter(annotation => 
                annotation.instanceId !== instanceId
            );
            
            // Update current instance if deleted
            if (this.currentInstanceId === instanceId) {
                this.currentInstanceId = this.couponInstances.size > 0 
                    ? Array.from(this.couponInstances.keys())[0] 
                    : null;
            }
            
            this.redrawCanvas();
            this.updateInstanceSelector();
            this.showToast('Coupon instance deleted', 'info');
        }
    }

    redrawCanvas() {
        // Clear canvas
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        
        // Draw image if loaded
        if (this.image.complete && this.image.naturalWidth > 0) {
            this.ctx.drawImage(this.image, 0, 0, this.canvas.width, this.canvas.height);
        }
        
        // Draw coupon instances (Stage 1)
        this.couponInstances.forEach((instance, instanceId) => {
            this.drawCouponInstance(instance, instanceId === this.currentInstanceId);
        });
        
        // Draw field annotations (Stage 2)
        this.annotations.forEach(annotation => {
            // Only show annotations for current instance in Stage 2
            if (this.annotationStage === 'stage2_fields') {
                if (annotation.instanceId === this.currentInstanceId) {
                    this.drawAnnotation(annotation);
                }
            } else {
                this.drawAnnotation(annotation);
            }
        });
        
        // Draw current annotation being drawn
        if (this.currentAnnotation && this.isDrawing) {
            this.drawAnnotation(this.currentAnnotation, true);
        }
    }
    
    drawCouponInstance(instance, isSelected = false) {
        const { boundary, status } = instance;
        
        const x = boundary.x * this.canvas.width;
        const y = boundary.y * this.canvas.height;
        const width = boundary.width * this.canvas.width;
        const height = boundary.height * this.canvas.height;
        
        // Set style based on status
        const statusColors = {
            'coupon_complete': '#00ff00',
            'coupon_partial_top': '#ffaa00',
            'coupon_partial_bottom': '#ff6600'
        };
        
        this.ctx.strokeStyle = statusColors[status] || '#00ff00';
        this.ctx.lineWidth = isSelected ? 4 : 3;
        this.ctx.setLineDash(isSelected ? [10, 5] : []);
        this.ctx.globalAlpha = 1.0;
        
        // Draw rectangle
        this.ctx.strokeRect(x, y, width, height);
        
        // Draw filled background with low opacity
        this.ctx.fillStyle = this.ctx.strokeStyle;
        this.ctx.globalAlpha = isSelected ? 0.2 : 0.1;
        this.ctx.fillRect(x, y, width, height);
        
        // Draw label
        this.ctx.globalAlpha = 1.0;
        this.ctx.fillStyle = this.ctx.strokeStyle;
        this.ctx.font = 'bold 16px Arial';
        
        const label = status.replace('coupon_', '').replace('_', ' ');
        const labelText = isSelected ? `● ${label}` : label;
        
        // Draw label with background
        const labelWidth = this.ctx.measureText(labelText).width;
        const labelHeight = 20;
        const labelX = x + 5;
        const labelY = y > labelHeight ? y - 5 : y + labelHeight;
        
        this.ctx.fillRect(labelX, labelY - labelHeight + 5, labelWidth + 10, labelHeight);
        this.ctx.fillStyle = 'white';
        this.ctx.fillText(labelText, labelX + 5, labelY - 5);
        
        // Reset drawing state
        this.ctx.setLineDash([]);
        this.ctx.globalAlpha = 1.0;
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
        if (this.annotationStage === 'stage1_coupons') {
            // Clear coupon instances
            this.couponInstances.clear();
            this.currentInstanceId = null;
            this.showToast('All coupon boundaries cleared', 'info');
        } else {
            // Clear field annotations for current instance
            if (this.currentInstanceId) {
                this.annotations = this.annotations.filter(annotation => 
                    annotation.instanceId !== this.currentInstanceId
                );
                
                // Clear from instance
                const instance = this.couponInstances.get(this.currentInstanceId);
                if (instance) {
                    instance.fields = [];
                }
                
                this.showToast('Field annotations cleared for current coupon', 'info');
            } else {
                this.annotations = [];
                this.showToast('All field annotations cleared', 'info');
            }
        }
        
        this.redrawCanvas();
    }

    undoLastAnnotation() {
        if (this.annotationStage === 'stage1_coupons') {
            // Remove last coupon instance
            if (this.couponInstances.size > 0) {
                const lastInstanceId = Array.from(this.couponInstances.keys()).pop();
                this.deleteCouponInstance(lastInstanceId);
            }
        } else {
            // Remove last field annotation for current instance
            if (this.currentInstanceId) {
                const instanceAnnotations = this.annotations.filter(annotation => 
                    annotation.instanceId === this.currentInstanceId
                );
                
                if (instanceAnnotations.length > 0) {
                    const lastAnnotation = instanceAnnotations[instanceAnnotations.length - 1];
                    this.annotations = this.annotations.filter(annotation => 
                        annotation.id !== lastAnnotation.id
                    );
                    
                    // Remove from instance
                    const instance = this.couponInstances.get(this.currentInstanceId);
                    if (instance) {
                        instance.fields = instance.fields.filter(field => 
                            field.createdAt !== lastAnnotation.createdAt
                        );
                    }
                    
                    this.showToast(`${this.getFieldDisplayName(lastAnnotation.fieldType)} annotation removed`, 'info');
                    this.redrawCanvas();
                }
            } else if (this.annotations.length > 0) {
                const removed = this.annotations.pop();
                this.showToast(`${this.getFieldDisplayName(removed.fieldType)} annotation removed`, 'info');
                this.redrawCanvas();
            }
        }
    }

    // Enhanced export for two-stage training
    exportForTraining() {
        const exportData = {
            imageClassification: this.imageClassification,
            annotationStage: this.annotationStage,
            couponInstances: Array.from(this.couponInstances.entries()).map(([id, instance]) => ({
                instanceId: id,
                boundary: instance.boundary,
                status: instance.status,
                fields: instance.fields,
                fieldCount: instance.fields.length
            })),
            fieldAnnotations: this.annotations.map(annotation => ({
                fieldType: annotation.fieldType,
                boundingBox: annotation.boundingBox,
                confidence: annotation.confidence,
                instanceId: annotation.instanceId,
                annotationStage: annotation.annotationStage,
                createdAt: annotation.createdAt
            })),
            trainingLabels: this.generateTwoStageLabels(),
            metadata: {
                totalCoupons: this.couponInstances.size,
                totalFields: this.annotations.length,
                imageWidth: this.canvas.width,
                imageHeight: this.canvas.height,
                createdAt: new Date().toISOString()
            }
        };
        
        return exportData;
    }
    
    generateTwoStageLabels() {
        const stage1Labels = []; // Coupon boundaries
        const stage2Labels = []; // Field detections
        
        // Stage 1: Coupon boundary labels
        const couponClassMap = {
            'coupon_complete': 0,
            'coupon_partial_top': 1,
            'coupon_partial_bottom': 2
        };
        
        this.couponInstances.forEach((instance, instanceId) => {
            const bbox = instance.boundary;
            const classId = couponClassMap[instance.status] || 0;
            
            stage1Labels.push({
                class_id: classId,
                center_x: bbox.x + bbox.width / 2,
                center_y: bbox.y + bbox.height / 2,
                width: bbox.width,
                height: bbox.height,
                confidence: 1.0,
                instance_id: instanceId
            });
        });
        
        // Stage 2: Field detection labels
        const fieldClassMap = {
            'code_region': 0,
            'benefit_region': 1,
            'expiry_region': 2,
            'app_region': 3,
            'terms_region': 4
        };
        
        this.annotations.forEach(annotation => {
            if (annotation.annotationStage === 'stage2_fields') {
                const bbox = annotation.boundingBox;
                const classId = fieldClassMap[annotation.fieldType] || 0;
                
                stage2Labels.push({
                    class_id: classId,
                    center_x: bbox.x + bbox.width / 2,
                    center_y: bbox.y + bbox.height / 2,
                    width: bbox.width,
                    height: bbox.height,
                    confidence: annotation.confidence || 1.0,
                    instance_id: annotation.instanceId,
                    field_type: annotation.fieldType
                });
            }
        });
        
        return {
            stage1_coupon_detection: stage1Labels,
            stage2_field_detection: stage2Labels
        };
    }

    getAnnotations() {
        return this.annotations.map(annotation => ({
            fieldType: annotation.fieldType,
            boundingBox: annotation.boundingBox || {
                x: annotation.startX / this.canvas.width,
                y: annotation.startY / this.canvas.height,
                width: annotation.width / this.canvas.width,
                height: annotation.height / this.canvas.height
            },
            confidence: annotation.confidence,
            instanceId: annotation.instanceId,
            annotationStage: annotation.annotationStage,
            createdAt: annotation.createdAt
        }));
    }

    loadAnnotations(annotations, couponInstances = []) {
        // Load coupon instances
        this.couponInstances.clear();
        couponInstances.forEach(instance => {
            this.couponInstances.set(instance.instanceId, {
                boundary: instance.boundary,
                status: instance.status,
                fields: instance.fields || [],
                createdAt: instance.createdAt
            });
        });
        
        // Load field annotations
        this.annotations = annotations.map(annotation => ({
            id: Date.now() + Math.random(),
            fieldType: annotation.fieldType,
            startX: annotation.boundingBox.x * this.canvas.width,
            startY: annotation.boundingBox.y * this.canvas.height,
            endX: (annotation.boundingBox.x + annotation.boundingBox.width) * this.canvas.width,
            endY: (annotation.boundingBox.y + annotation.boundingBox.height) * this.canvas.height,
            width: annotation.boundingBox.width * this.canvas.width,
            height: annotation.boundingBox.height * this.canvas.height,
            boundingBox: annotation.boundingBox,
            color: this.fieldColors[annotation.fieldType],
            confidence: annotation.confidence || 1.0,
            instanceId: annotation.instanceId,
            annotationStage: annotation.annotationStage || 'stage2_fields',
            createdAt: annotation.createdAt
        }));
        
        // Update UI
        this.updateStageUI();
        this.redrawCanvas();
    }

    setImage(imageSrc) {
        return new Promise((resolve) => {
            this.image.onload = async () => {
                // Set canvas dimensions to match image aspect ratio
                const maxWidth = 800;
                const maxHeight = 600;
                
                let { width, height } = this.image;
                const ratio = Math.min(maxWidth / width, maxHeight / height);
                
                width *= ratio;
                height *= ratio;
                
                this.canvas.width = width;
                this.canvas.height = height;
                
                // Update canvas display size
                this.canvas.style.width = width + 'px';
                this.canvas.style.height = height + 'px';
                
                // Store original dimensions for export
                this.originalWidth = this.image.naturalWidth;
                this.originalHeight = this.image.naturalHeight;
                
                this.redrawCanvas();
                resolve({ width: this.canvas.width, height: this.canvas.height });
            };
            
            this.image.src = imageSrc;
        });
    }

    getFieldDisplayName(fieldType) {
        const displayNames = {
            // Stage 1
            'coupon_complete': 'Complete',
            'coupon_partial_top': 'Partial Top',
            'coupon_partial_bottom': 'Partial Bottom',
            // Stage 2
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
        
        const container = document.getElementById('toast-container') || document.body;
        container.appendChild(toast);
        
        // Auto remove after 3 seconds
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 3000);
    }

    // Get enhanced annotation statistics
    getStats() {
        const stats = {
            stage1: {
                totalInstances: this.couponInstances.size,
                complete: 0,
                partialTop: 0,
                partialBottom: 0
            },
            stage2: {
                totalFields: this.annotations.length,
                fieldCounts: {},
                instanceFieldCounts: {}
            },
            imageClassification: this.imageClassification,
            coverage: this.calculateCoverage()
        };
        
        // Stage 1 stats
        this.couponInstances.forEach((instance) => {
            switch (instance.status) {
                case 'coupon_complete':
                    stats.stage1.complete++;
                    break;
                case 'coupon_partial_top':
                    stats.stage1.partialTop++;
                    break;
                case 'coupon_partial_bottom':
                    stats.stage1.partialBottom++;
                    break;
            }
        });
        
        // Stage 2 stats
        this.annotations.forEach(annotation => {
            stats.stage2.fieldCounts[annotation.fieldType] = 
                (stats.stage2.fieldCounts[annotation.fieldType] || 0) + 1;
                
            if (annotation.instanceId) {
                if (!stats.stage2.instanceFieldCounts[annotation.instanceId]) {
                    stats.stage2.instanceFieldCounts[annotation.instanceId] = 0;
                }
                stats.stage2.instanceFieldCounts[annotation.instanceId]++;
            }
        });

        return stats;
    }

    calculateCoverage() {
        if (this.couponInstances.size === 0 && this.annotations.length === 0) return 0;
        
        const totalArea = this.canvas.width * this.canvas.height;
        let annotatedArea = 0;
        
        // Add coupon instance areas
        this.couponInstances.forEach((instance) => {
            const bbox = instance.boundary;
            annotatedArea += (bbox.width * this.canvas.width) * (bbox.height * this.canvas.height);
        });
        
        // Add field annotation areas
        this.annotations.forEach(annotation => {
            annotatedArea += annotation.width * annotation.height;
        });
        
        return Math.round((annotatedArea / totalArea) * 100);
    }
}

// Enhanced Annotation Manager for Multi-Coupon Training
class MultiCouponAnnotationManager {
    constructor() {
        this.images = [];
        this.currentImageIndex = 0;
        this.annotationCanvas = null;
        this.isInitialized = false;
        this.currentCouponId = null;
    }

    init(canvasId = 'annotation-canvas', imageId = 'current-image') {
        this.annotationCanvas = new MultiCouponAnnotationCanvas(canvasId, imageId);
        this.isInitialized = true;
        this.setupUI();
        console.log('Multi-coupon annotation manager initialized');
    }

    setupUI() {
        // Stage toggle buttons
        const stage1Btn = document.getElementById('stage1-btn');
        const stage2Btn = document.getElementById('stage2-btn');
        
        if (stage1Btn) {
            stage1Btn.addEventListener('click', () => {
                this.annotationCanvas.switchToStage1();
                stage1Btn.classList.add('active');
                stage2Btn.classList.remove('active');
            });
        }
        
        if (stage2Btn) {
            stage2Btn.addEventListener('click', () => {
                if (this.annotationCanvas.switchToStage2()) {
                    stage2Btn.classList.add('active');
                    stage1Btn.classList.remove('active');
                }
            });
        }
        
        // Auto-detect button
        const autoDetectBtn = document.getElementById('auto-detect-btn');
        if (autoDetectBtn) {
            autoDetectBtn.addEventListener('click', () => {
                this.annotationCanvas.autoDetectCouponLayout();
            });
        }
        
        // Classification buttons
        document.querySelectorAll('.classify-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.classify-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                
                const type = btn.dataset.type;
                if (type && type !== 'auto-detect') {
                    this.annotationCanvas.imageClassification = type;
                    this.updateCouponMetadata();
                    this.annotationCanvas.showToast(`Image classified as: ${type.replace('_', ' ')}`, 'info');
                }
            });
        });
        
        // Field type buttons
        document.querySelectorAll('.field-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                // Only toggle within the current stage
                const currentStageClass = this.annotationCanvas.annotationStage === 'stage1_coupons' 
                    ? '.stage1-btn' : '.stage2-btn';
                
                document.querySelectorAll(currentStageClass).forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this.annotationCanvas.setFieldType(btn.dataset.field);
            });
        });

        // Action buttons
        const clearBtn = document.getElementById('clear-annotations');
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                const stageName = this.annotationCanvas.annotationStage === 'stage1_coupons' 
                    ? 'coupon boundaries' : 'field annotations';
                if (confirm(`Clear all ${stageName} for this image?`)) {
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
        
        // Export button
        const exportBtn = document.getElementById('export-training-data');
        if (exportBtn) {
            exportBtn.addEventListener('click', () => {
                this.exportTrainingDataset();
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

    async addImages(imageFiles) {
        const promises = imageFiles.map(file => {
            return new Promise((resolve) => {
                const reader = new FileReader();
                reader.onload = (e) => {
                    resolve({
                        file: file,
                        name: file.name,
                        data: e.target.result,
                        annotations: [],
                        couponInstances: [],
                        imageClassification: 'unknown',
                        status: 'pending'
                    });
                };
                reader.readAsDataURL(file);
            });
        });

        const images = await Promise.all(promises);
        this.images = images;
        this.currentImageIndex = 0;
        this.updateUI();
        
        if (images.length > 0) {
            await this.loadImage(0);
        }
        
        return images;
    }

    async loadImage(index) {
        if (index < 0 || index >= this.images.length) return;
        
        this.currentImageIndex = index;
        const image = this.images[index];
        
        // Save current image to storage if not already saved
        if (!this.currentCouponId) {
            this.currentCouponId = await storage.saveCoupon(image.data, image.name);
        }
        
        const dimensions = await this.annotationCanvas.setImage(image.data);
        
        // Update coupon metadata with dimensions
        await storage.updateCouponMetadata(this.currentCouponId, {
            imageWidth: dimensions.width,
            imageHeight: dimensions.height,
            imageClassification: image.imageClassification
        });
        
        // Load existing annotations if any
        if (image.annotations.length > 0 || image.couponInstances.length > 0) {
            this.annotationCanvas.loadAnnotations(image.annotations, image.couponInstances);
        }
        
        // Set image classification
        this.annotationCanvas.imageClassification = image.imageClassification;
        this.updateClassificationUI();
        this.updateUI();
        
        return dimensions;
    }
    
    updateClassificationUI() {
        const classifyBtns = document.querySelectorAll('.classify-btn');
        classifyBtns.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.type === this.annotationCanvas.imageClassification);
        });
    }

    async updateCouponMetadata() {
        if (this.currentCouponId) {
            await storage.updateCouponMetadata(this.currentCouponId, {
                imageClassification: this.annotationCanvas.imageClassification,
                annotationStage: this.annotationCanvas.annotationStage,
                totalCoupons: this.annotationCanvas.couponInstances.size
            });
        }
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

    async saveCurrentAnnotations() {
        if (this.images.length === 0 || !this.annotationCanvas || !this.currentCouponId) return;
        
        const currentImage = this.images[this.currentImageIndex];
        const exportData = this.annotationCanvas.exportForTraining();
        
        // Update image data
        currentImage.annotations = exportData.fieldAnnotations;
        currentImage.couponInstances = exportData.couponInstances;
        currentImage.imageClassification = exportData.imageClassification;
        currentImage.status = (exportData.couponInstances.length > 0 || exportData.fieldAnnotations.length > 0) 
            ? 'annotated' : 'pending';
        
        try {
            // Save annotations to storage
            for (const annotation of exportData.fieldAnnotations) {
                await storage.saveAnnotation(this.currentCouponId, annotation);
            }
            
            // Save coupon instances to storage
            for (const instance of exportData.couponInstances) {
                await storage.saveCouponInstance(this.currentCouponId, {
                    instanceId: instance.instanceId,
                    boundingBox: instance.boundary,
                    status: instance.status,
                    confidence: 1.0
                });
            }
            
            // Update coupon metadata and status
            await this.updateCouponMetadata();
            await storage.updateCouponStatus(this.currentCouponId, currentImage.status);
            
            console.log('Enhanced annotations saved for:', currentImage.name);
            
        } catch (error) {
            console.error('Failed to save annotations:', error);
            this.annotationCanvas.showToast('Failed to save annotations', 'error');
        }
    }

    async exportTrainingDataset() {
        try {
            // Save current annotations first
            await this.saveCurrentAnnotations();
            
            this.annotationCanvas.showToast('Exporting training dataset...', 'info');
            
            // Export complete dataset from storage
            const dataset = await storage.exportTrainingDataset();
            
            // Download as JSON file
            const filename = `multi_coupon_training_${new Date().toISOString().split('T')[0]}.json`;
            StorageUtils.downloadJSON(dataset, filename);
            
            this.annotationCanvas.showToast(`Training dataset exported: ${filename}`, 'success');
            
            console.log('Training dataset exported:', {
                totalImages: dataset.totalImages,
                stage1Instances: dataset.stage1_coupon_detection.length,
                stage2Fields: dataset.stage2_field_detection.length
            });
            
            return dataset;
            
        } catch (error) {
            console.error('Failed to export training dataset:', error);
            this.annotationCanvas.showToast('Export failed: ' + error.message, 'error');
        }
    }

    getAnnotationSummary() {
        let totalAnnotations = 0;
        let totalInstances = 0;
        let annotatedImages = 0;
        const fieldCounts = {};
        const classificationCounts = { single: 0, multi_grid: 0, scrollable: 0, unknown: 0 };
        
        this.images.forEach(image => {
            if (image.annotations.length > 0 || image.couponInstances.length > 0) {
                annotatedImages++;
                totalAnnotations += image.annotations.length;
                totalInstances += image.couponInstances.length;
                
                image.annotations.forEach(annotation => {
                    fieldCounts[annotation.fieldType] = (fieldCounts[annotation.fieldType] || 0) + 1;
                });
            }
            
            classificationCounts[image.imageClassification]++;
        });
        
        return {
            totalImages: this.images.length,
            annotatedImages,
            totalAnnotations,
            totalInstances,
            fieldCounts,
            classificationCounts,
            completionRate: Math.round((annotatedImages / this.images.length) * 100)
        };
    }
}

// Global annotation manager
let annotationManager = null;

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('annotation-canvas')) {
        annotationManager = new MultiCouponAnnotationManager();
        annotationManager.init();
        window.annotationManager = annotationManager; // Make globally accessible
        console.log('Multi-coupon annotation system ready');
    }
});

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { MultiCouponAnnotationCanvas, MultiCouponAnnotationManager };
} else {
    window.MultiCouponAnnotationCanvas = MultiCouponAnnotationCanvas;
    window.MultiCouponAnnotationManager = MultiCouponAnnotationManager;
}
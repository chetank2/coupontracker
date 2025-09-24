/**
 * Mobile Zoom Handler for Coupon Annotation
 * Handles zoom controls, pinch-to-zoom, and canvas scaling
 */

class MobileZoomHandler {
    constructor() {
        this.scale = 1;
        this.maxScale = 3;
        this.minScale = 0.5;
        this.scaleStep = 0.2;
        
        this.container = null;
        this.zoomContainer = null;
        this.canvas = null;
        this.image = null;
        
        // Touch gesture variables
        this.initialDistance = 0;
        this.initialScale = 1;
        this.isZooming = false;
        
        // Pan variables
        this.isPanning = false;
        this.lastPanPoint = { x: 0, y: 0 };
        this.panOffset = { x: 0, y: 0 };
        
        this.init();
    }
    
    init() {
        // Wait for DOM to be ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.setup());
        } else {
            this.setup();
        }
    }
    
    setup() {
        this.container = document.querySelector('.canvas-container');
        this.zoomContainer = document.getElementById('zoom-container');
        this.canvas = document.getElementById('annotation-canvas');
        this.image = document.getElementById('current-image');
        
        if (!this.container || !this.zoomContainer) {
            console.log('Zoom elements not found, skipping mobile zoom setup');
            return;
        }
        
        this.setupZoomControls();
        this.setupTouchGestures();
        this.setupMouseEvents();
        
        console.log('Mobile zoom handler initialized');
    }
    
    setupZoomControls() {
        const zoomInBtn = document.getElementById('zoom-in-btn');
        const zoomOutBtn = document.getElementById('zoom-out-btn');
        const zoomResetBtn = document.getElementById('zoom-reset-btn');
        const fitScreenBtn = document.getElementById('fit-screen-btn');
        
        if (zoomInBtn) zoomInBtn.addEventListener('click', () => this.zoomIn());
        if (zoomOutBtn) zoomOutBtn.addEventListener('click', () => this.zoomOut());
        if (zoomResetBtn) zoomResetBtn.addEventListener('click', () => this.resetZoom());
        if (fitScreenBtn) fitScreenBtn.addEventListener('click', () => this.fitToScreen());
        
        this.updateZoomButtons();
    }
    
    setupTouchGestures() {
        if (!this.zoomContainer) return;
        
        // Prevent default touch behaviors
        this.zoomContainer.addEventListener('touchstart', (e) => this.handleTouchStart(e), { passive: false });
        this.zoomContainer.addEventListener('touchmove', (e) => this.handleTouchMove(e), { passive: false });
        this.zoomContainer.addEventListener('touchend', (e) => this.handleTouchEnd(e), { passive: false });
    }
    
    setupMouseEvents() {
        if (!this.zoomContainer) return;
        
        // Mouse wheel zoom
        this.zoomContainer.addEventListener('wheel', (e) => this.handleWheel(e), { passive: false });
        
        // Mouse pan
        this.zoomContainer.addEventListener('mousedown', (e) => this.handleMouseDown(e));
        this.zoomContainer.addEventListener('mousemove', (e) => this.handleMouseMove(e));
        this.zoomContainer.addEventListener('mouseup', (e) => this.handleMouseUp(e));
        this.zoomContainer.addEventListener('mouseleave', (e) => this.handleMouseUp(e));
    }
    
    handleTouchStart(e) {
        if (e.touches.length === 2) {
            // Two-finger pinch start
            e.preventDefault();
            this.isZooming = true;
            this.initialDistance = this.getDistance(e.touches[0], e.touches[1]);
            this.initialScale = this.scale;
        } else if (e.touches.length === 1 && this.scale > 1) {
            // Single finger pan start (only when zoomed)
            this.isPanning = true;
            this.lastPanPoint = {
                x: e.touches[0].clientX,
                y: e.touches[0].clientY
            };
        }
    }
    
    handleTouchMove(e) {
        if (e.touches.length === 2 && this.isZooming) {
            // Two-finger pinch zoom
            e.preventDefault();
            const currentDistance = this.getDistance(e.touches[0], e.touches[1]);
            const scaleChange = currentDistance / this.initialDistance;
            const newScale = this.initialScale * scaleChange;
            
            this.setZoom(newScale);
        } else if (e.touches.length === 1 && this.isPanning && this.scale > 1) {
            // Single finger pan
            e.preventDefault();
            const currentPoint = {
                x: e.touches[0].clientX,
                y: e.touches[0].clientY
            };
            
            const deltaX = currentPoint.x - this.lastPanPoint.x;
            const deltaY = currentPoint.y - this.lastPanPoint.y;
            
            this.panOffset.x += deltaX;
            this.panOffset.y += deltaY;
            
            this.updateTransform();
            
            this.lastPanPoint = currentPoint;
        }
    }
    
    handleTouchEnd(e) {
        if (e.touches.length < 2) {
            this.isZooming = false;
        }
        if (e.touches.length === 0) {
            this.isPanning = false;
        }
    }
    
    handleWheel(e) {
        e.preventDefault();
        const delta = e.deltaY > 0 ? -this.scaleStep : this.scaleStep;
        const newScale = this.scale + delta;
        this.setZoom(newScale);
    }
    
    handleMouseDown(e) {
        if (this.scale > 1) {
            this.isPanning = true;
            this.lastPanPoint = { x: e.clientX, y: e.clientY };
            this.zoomContainer.style.cursor = 'grabbing';
        }
    }
    
    handleMouseMove(e) {
        if (this.isPanning && this.scale > 1) {
            const deltaX = e.clientX - this.lastPanPoint.x;
            const deltaY = e.clientY - this.lastPanPoint.y;
            
            this.panOffset.x += deltaX;
            this.panOffset.y += deltaY;
            
            this.updateTransform();
            
            this.lastPanPoint = { x: e.clientX, y: e.clientY };
        }
    }
    
    handleMouseUp(e) {
        this.isPanning = false;
        this.zoomContainer.style.cursor = this.scale > 1 ? 'grab' : 'default';
    }
    
    getDistance(touch1, touch2) {
        const dx = touch1.clientX - touch2.clientX;
        const dy = touch1.clientY - touch2.clientY;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    zoomIn() {
        this.setZoom(this.scale + this.scaleStep);
    }
    
    zoomOut() {
        this.setZoom(this.scale - this.scaleStep);
    }
    
    resetZoom() {
        this.scale = 1;
        this.panOffset = { x: 0, y: 0 };
        this.updateTransform();
        this.updateZoomButtons();
    }
    
    fitToScreen() {
        if (!this.image || !this.container) return;
        
        const containerRect = this.container.getBoundingClientRect();
        const imageRect = this.image.getBoundingClientRect();
        
        if (imageRect.width === 0 || imageRect.height === 0) return;
        
        const scaleX = containerRect.width / imageRect.width;
        const scaleY = containerRect.height / imageRect.height;
        const fitScale = Math.min(scaleX, scaleY, 1); // Don't scale up beyond 100%
        
        this.scale = fitScale;
        this.panOffset = { x: 0, y: 0 };
        this.updateTransform();
        this.updateZoomButtons();
    }
    
    setZoom(newScale) {
        this.scale = Math.max(this.minScale, Math.min(this.maxScale, newScale));
        this.updateTransform();
        this.updateZoomButtons();
        
        // Reset pan if zoomed out to 1x or less
        if (this.scale <= 1) {
            this.panOffset = { x: 0, y: 0 };
            this.updateTransform();
        }
    }
    
    updateTransform() {
        if (!this.zoomContainer) return;
        
        const transform = `scale(${this.scale}) translate(${this.panOffset.x / this.scale}px, ${this.panOffset.y / this.scale}px)`;
        this.zoomContainer.style.transform = transform;
        
        // Update cursor
        this.zoomContainer.style.cursor = this.scale > 1 ? 'grab' : 'default';
        
        // Update canvas size for annotation accuracy
        this.updateCanvasCoordinates();
    }
    
    updateCanvasCoordinates() {
        // Notify annotation manager about zoom changes
        if (window.annotationManager && typeof window.annotationManager.updateZoom === 'function') {
            window.annotationManager.updateZoom(this.scale, this.panOffset);
        }
    }
    
    updateZoomButtons() {
        const zoomInBtn = document.getElementById('zoom-in-btn');
        const zoomOutBtn = document.getElementById('zoom-out-btn');
        
        if (zoomInBtn) {
            zoomInBtn.disabled = this.scale >= this.maxScale;
        }
        
        if (zoomOutBtn) {
            zoomOutBtn.disabled = this.scale <= this.minScale;
        }
    }
    
    // Public method to get current zoom info
    getZoomInfo() {
        return {
            scale: this.scale,
            panOffset: { ...this.panOffset },
            maxScale: this.maxScale,
            minScale: this.minScale
        };
    }
    
    // Public method to set zoom programmatically
    setZoomLevel(scale, panX = 0, panY = 0) {
        this.scale = Math.max(this.minScale, Math.min(this.maxScale, scale));
        this.panOffset = { x: panX, y: panY };
        this.updateTransform();
        this.updateZoomButtons();
    }
}

// Initialize mobile zoom handler
let mobileZoomHandler = null;

// Initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        mobileZoomHandler = new MobileZoomHandler();
        window.mobileZoomHandler = mobileZoomHandler; // Make globally accessible
    });
} else {
    mobileZoomHandler = new MobileZoomHandler();
    window.mobileZoomHandler = mobileZoomHandler; // Make globally accessible
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = MobileZoomHandler;
}

/**
 * Mobile Image Handler for Coupon Annotation
 * Handles simple image controls: Reset and Fit to Screen
 */

class MobileZoomHandler {
    constructor() {
        this.scale = 1;
        this.maxScale = 2;
        this.minScale = 0.5;
        this.scaleStep = 0.5;
        
        this.container = null;
        this.zoomContainer = null;
        this.canvas = null;
        this.image = null;
        
        // Simple pan variables (for reset/fit functionality)
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
        this.scrollIndicator = document.getElementById('scroll-indicator');
        
        if (!this.container || !this.zoomContainer) {
            console.log('Zoom elements not found, skipping mobile zoom setup');
            return;
        }
        
        this.setupZoomControls();
        this.setupScrollHandler();
        // Remove complex touch gestures and mouse events
        // this.setupTouchGestures();
        // this.setupMouseEvents();
        
        console.log('Mobile image handler initialized (simple mode)');
    }
    
    setupZoomControls() {
        const zoomResetBtn = document.getElementById('zoom-reset-btn');
        
        if (zoomResetBtn) zoomResetBtn.addEventListener('click', () => this.resetScroll());
        
        console.log('Full-width scroll controls setup complete');
    }
    
    setupScrollHandler() {
        if (!this.container) return;
        
        // Show/hide scroll indicator based on content height
        this.container.addEventListener('scroll', () => {
            this.updateScrollIndicator();
        });
        
        // Initial check when image loads
        if (this.image) {
            this.image.addEventListener('load', () => {
                setTimeout(() => this.updateScrollIndicator(), 100);
            });
        }
        
        console.log('Scroll handler setup complete');
    }
    
    updateScrollIndicator() {
        if (!this.container || !this.scrollIndicator) return;
        
        const { scrollTop, scrollHeight, clientHeight } = this.container;
        const isScrollable = scrollHeight > clientHeight;
        const isAtBottom = scrollTop + clientHeight >= scrollHeight - 10;
        
        // Show indicator if content is scrollable and not at bottom
        if (isScrollable && !isAtBottom && scrollTop < 50) {
            this.scrollIndicator.classList.add('show');
        } else {
            this.scrollIndicator.classList.remove('show');
        }
    }
    
    // Removed complex touch gestures and mouse events for simplicity
    
    resetScroll() {
        // Reset scroll position to top
        if (this.container) {
            this.container.scrollTop = 0;
            this.container.scrollLeft = 0;
            console.log('Scroll position reset to top');
        }
        
        // Also reset any transform if present
        this.scale = 1;
        this.panOffset = { x: 0, y: 0 };
        this.updateTransform();
        console.log('Image reset to full width');
    }
    
    updateTransform() {
        if (!this.zoomContainer) return;
        
        const transform = `scale(${this.scale}) translate(${this.panOffset.x / this.scale}px, ${this.panOffset.y / this.scale}px)`;
        this.zoomContainer.style.transform = transform;
        
        // Keep cursor simple
        this.zoomContainer.style.cursor = 'default';
        
        // Update canvas size for annotation accuracy
        this.updateCanvasCoordinates();
    }
    
    updateCanvasCoordinates() {
        // Notify annotation manager about zoom changes
        if (window.annotationManager && typeof window.annotationManager.updateZoom === 'function') {
            window.annotationManager.updateZoom(this.scale, this.panOffset);
        }
    }
    
    // No zoom buttons to update anymore
    
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

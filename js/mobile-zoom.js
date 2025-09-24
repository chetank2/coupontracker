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
        this.customScrollbar = document.getElementById('custom-scrollbar');
        this.scrollbarThumb = document.getElementById('scrollbar-thumb');
        
        if (!this.container || !this.zoomContainer) {
            console.log('Zoom elements not found, skipping mobile zoom setup');
            return;
        }
        
        this.setupZoomControls();
        this.setupScrollHandler();
        this.setupCustomScrollbar();
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
        
        // Update custom scrollbar
        this.updateCustomScrollbar();
    }
    
    setupCustomScrollbar() {
        if (!this.customScrollbar || !this.scrollbarThumb || !this.container) return;
        
        let isDragging = false;
        let startY = 0;
        let startScrollTop = 0;
        
        // Mouse events
        this.scrollbarThumb.addEventListener('mousedown', (e) => {
            isDragging = true;
            startY = e.clientY;
            startScrollTop = this.container.scrollTop;
            this.scrollbarThumb.classList.add('dragging');
            e.preventDefault();
        });
        
        // Touch events for mobile
        this.scrollbarThumb.addEventListener('touchstart', (e) => {
            isDragging = true;
            startY = e.touches[0].clientY;
            startScrollTop = this.container.scrollTop;
            this.scrollbarThumb.classList.add('dragging');
            e.preventDefault();
        }, { passive: false });
        
        // Global mouse/touch move events
        document.addEventListener('mousemove', (e) => {
            if (!isDragging) return;
            this.handleScrollbarDrag(e.clientY, startY, startScrollTop);
        });
        
        document.addEventListener('touchmove', (e) => {
            if (!isDragging) return;
            this.handleScrollbarDrag(e.touches[0].clientY, startY, startScrollTop);
        }, { passive: false });
        
        // Global mouse/touch end events
        document.addEventListener('mouseup', () => {
            if (isDragging) {
                isDragging = false;
                this.scrollbarThumb.classList.remove('dragging');
            }
        });
        
        document.addEventListener('touchend', () => {
            if (isDragging) {
                isDragging = false;
                this.scrollbarThumb.classList.remove('dragging');
            }
        });
        
        // Click on track to jump
        this.customScrollbar.addEventListener('click', (e) => {
            if (e.target === this.scrollbarThumb) return;
            
            const rect = this.customScrollbar.getBoundingClientRect();
            const clickY = e.clientY - rect.top;
            const trackHeight = rect.height - 8; // Account for padding
            const scrollRatio = clickY / trackHeight;
            
            const { scrollHeight, clientHeight } = this.container;
            const maxScroll = scrollHeight - clientHeight;
            this.container.scrollTop = maxScroll * scrollRatio;
        });
        
        console.log('Custom scrollbar setup complete');
    }
    
    handleScrollbarDrag(currentY, startY, startScrollTop) {
        const deltaY = currentY - startY;
        const { scrollHeight, clientHeight } = this.container;
        const maxScroll = scrollHeight - clientHeight;
        
        // Calculate scroll ratio based on scrollbar height
        const scrollbarHeight = this.customScrollbar.getBoundingClientRect().height - 8;
        const scrollRatio = deltaY / scrollbarHeight;
        
        const newScrollTop = startScrollTop + (maxScroll * scrollRatio);
        this.container.scrollTop = Math.max(0, Math.min(maxScroll, newScrollTop));
    }
    
    updateCustomScrollbar() {
        if (!this.customScrollbar || !this.scrollbarThumb || !this.container) return;
        
        const { scrollTop, scrollHeight, clientHeight } = this.container;
        const isScrollable = scrollHeight > clientHeight;
        
        if (isScrollable) {
            // Show scrollbar
            this.customScrollbar.classList.add('visible');
            
            // Calculate thumb position and size
            const scrollRatio = scrollTop / (scrollHeight - clientHeight);
            const thumbRatio = clientHeight / scrollHeight;
            
            // Update thumb position and size
            const trackHeight = this.customScrollbar.getBoundingClientRect().height - 8;
            const thumbHeight = Math.max(40, trackHeight * thumbRatio);
            const thumbTop = (trackHeight - thumbHeight) * scrollRatio;
            
            this.scrollbarThumb.style.height = thumbHeight + 'px';
            this.scrollbarThumb.style.top = (thumbTop + 4) + 'px';
        } else {
            // Hide scrollbar
            this.customScrollbar.classList.remove('visible');
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

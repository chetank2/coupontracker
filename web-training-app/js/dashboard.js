/**
 * Dashboard Management for Web Training App
 * Handles dashboard statistics, training progress, and user interactions
 */

class TrainingDashboard {
    constructor() {
        this.stats = {
            totalCoupons: 0,
            annotatedCoupons: 0,
            modelAccuracy: null,
            trainingStatus: 'idle'
        };
        
        this.trainingSession = null;
        this.chart = null;
        
        this.init();
    }
    
    async init() {
        console.log('Initializing training dashboard...');
        
        // Wait for storage to be ready
        if (!window.trainingStorage || !window.trainingStorage.db) {
            setTimeout(() => this.init(), 100);
            return;
        }
        
        await this.loadStats();
        this.setupEventListeners();
        this.setupChart();
        await this.loadActivity();
        
        console.log('Training dashboard initialized');
    }
    
    async loadStats() {
        try {
            const stats = await trainingStorage.getStats();
            this.stats = { ...this.stats, ...stats };
            this.updateStatsUI();
        } catch (error) {
            console.error('Failed to load stats:', error);
        }
    }
    
    updateStatsUI() {
        // Update stat numbers
        const elements = {
            'total-coupons': this.stats.totalCoupons,
            'annotated-coupons': this.stats.annotatedCoupons,
            'model-accuracy': this.stats.latestModelAccuracy ? 
                `${(this.stats.latestModelAccuracy * 100).toFixed(1)}%` : '--',
            'training-status': this.getTrainingStatusText()
        };
        
        Object.entries(elements).forEach(([id, value]) => {
            const element = document.getElementById(id);
            if (element) {
                element.textContent = value;
            }
        });
        
        // Update progress bars
        const annotationProgress = document.getElementById('annotation-progress');
        const annotationPercentage = document.getElementById('annotation-percentage');
        
        if (annotationProgress && annotationPercentage) {
            const percentage = Math.round(this.stats.annotationProgress || 0);
            annotationProgress.style.width = `${percentage}%`;
            annotationPercentage.textContent = `${percentage}%`;
        }
        
        // Update accuracy change indicator
        const accuracyChange = document.getElementById('accuracy-change');
        if (accuracyChange) {
            if (this.stats.latestModelAccuracy) {
                accuracyChange.textContent = `Last training: ${this.formatDate(this.stats.lastTraining)}`;
                accuracyChange.className = 'stat-change positive';
            } else {
                accuracyChange.textContent = 'No training yet';
                accuracyChange.className = 'stat-change';
            }
        }
        
        // Update training detail
        const trainingDetail = document.getElementById('training-detail');
        if (trainingDetail) {
            trainingDetail.textContent = this.getTrainingDetailText();
        }
    }
    
    getTrainingStatusText() {
        switch (this.stats.trainingStatus) {
            case 'training': return 'Training';
            case 'evaluating': return 'Evaluating';
            case 'completed': return 'Completed';
            case 'failed': return 'Failed';
            default: return 'Idle';
        }
    }
    
    getTrainingDetailText() {
        if (this.stats.annotatedCoupons === 0) {
            return 'Upload and annotate coupons to start';
        } else if (this.stats.annotatedCoupons < 10) {
            return `Need ${10 - this.stats.annotatedCoupons} more annotations`;
        } else {
            return 'Ready to train';
        }
    }
    
    setupEventListeners() {
        // Navigation toggle
        const navToggle = document.getElementById('nav-toggle');
        const navMenu = document.getElementById('nav-menu');
        
        if (navToggle && navMenu) {
            navToggle.addEventListener('click', () => {
                navMenu.classList.toggle('active');
            });
        }
        
        // Quick upload button
        const quickUploadBtn = document.getElementById('quick-upload-btn');
        if (quickUploadBtn) {
            quickUploadBtn.addEventListener('click', () => {
                window.location.href = './upload.html';
            });
        }
        
        // Start training button
        const startTrainingBtn = document.getElementById('start-training-btn');
        if (startTrainingBtn) {
            startTrainingBtn.addEventListener('click', () => this.showTrainingModal());
        }
        
        // Training modal
        const trainingModal = document.getElementById('training-modal');
        const closeTrainingModal = document.getElementById('close-training-modal');
        const cancelTrainingBtn = document.getElementById('cancel-training-btn');
        const confirmTrainingBtn = document.getElementById('confirm-training-btn');
        
        if (closeTrainingModal) {
            closeTrainingModal.addEventListener('click', () => this.hideTrainingModal());
        }
        
        if (cancelTrainingBtn) {
            cancelTrainingBtn.addEventListener('click', () => this.hideTrainingModal());
        }
        
        if (confirmTrainingBtn) {
            confirmTrainingBtn.addEventListener('click', () => this.startTraining());
        }
        
        // Other action buttons
        const viewLogsBtn = document.getElementById('view-logs-btn');
        if (viewLogsBtn) {
            viewLogsBtn.addEventListener('click', () => this.showToast('info', 'Training logs will be available in the next version'));
        }
        
        const downloadModelBtn = document.getElementById('download-model-btn');
        if (downloadModelBtn) {
            downloadModelBtn.addEventListener('click', () => this.downloadLatestModel());
        }
        
        const clearActivityBtn = document.getElementById('clear-activity-btn');
        if (clearActivityBtn) {
            clearActivityBtn.addEventListener('click', () => this.clearActivity());
        }
        
        // Click outside modal to close
        const modal = document.getElementById('training-modal');
        if (modal) {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) {
                    this.hideTrainingModal();
                }
            });
        }
    }
    
    setupChart() {
        const canvas = document.getElementById('training-chart');
        if (!canvas || !window.Chart) {
            console.warn('Chart.js not available or canvas not found');
            return;
        }
        
        const ctx = canvas.getContext('2d');
        this.chart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Training Loss',
                    data: [],
                    borderColor: '#667eea',
                    backgroundColor: 'rgba(102, 126, 234, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4
                }, {
                    label: 'Validation Accuracy',
                    data: [],
                    borderColor: '#11998e',
                    backgroundColor: 'rgba(17, 153, 142, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4,
                    yAxisID: 'y1'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: true,
                        position: 'top'
                    }
                },
                scales: {
                    y: {
                        type: 'linear',
                        display: true,
                        position: 'left',
                        title: {
                            display: true,
                            text: 'Loss'
                        }
                    },
                    y1: {
                        type: 'linear',
                        display: true,
                        position: 'right',
                        title: {
                            display: true,
                            text: 'Accuracy (%)'
                        },
                        grid: {
                            drawOnChartArea: false
                        }
                    }
                }
            }
        });
        
        // Load existing training data
        this.loadTrainingHistory();
    }
    
    async loadTrainingHistory() {
        try {
            const sessions = await trainingStorage.getSessions();
            const completedSessions = sessions.filter(s => s.status === 'completed' && s.metrics);
            
            if (completedSessions.length > 0 && this.chart) {
                const labels = completedSessions.map((_, index) => `Session ${index + 1}`);
                const lossData = completedSessions.map(s => s.metrics.finalLoss || 0);
                const accuracyData = completedSessions.map(s => (s.metrics.accuracy || 0) * 100);
                
                this.chart.data.labels = labels;
                this.chart.data.datasets[0].data = lossData;
                this.chart.data.datasets[1].data = accuracyData;
                this.chart.update();
            }
        } catch (error) {
            console.error('Failed to load training history:', error);
        }
    }
    
    async loadActivity() {
        try {
            const activities = await trainingStorage.getActivity(5);
            const activityFeed = document.getElementById('activity-feed');
            
            if (!activityFeed) return;
            
            if (activities.length === 0) {
                activityFeed.innerHTML = `
                    <div class="activity-item">
                        <div class="activity-icon welcome">🎉</div>
                        <div class="activity-content">
                            <div class="activity-title">Welcome to Coupon Trainer!</div>
                            <div class="activity-description">Start by uploading your first coupon images</div>
                            <div class="activity-time">Just now</div>
                        </div>
                    </div>
                `;
                return;
            }
            
            activityFeed.innerHTML = activities.map(activity => `
                <div class="activity-item">
                    <div class="activity-icon ${activity.type}">
                        ${this.getActivityIcon(activity.type)}
                    </div>
                    <div class="activity-content">
                        <div class="activity-title">${activity.title}</div>
                        <div class="activity-description">${activity.description}</div>
                        <div class="activity-time">${this.formatRelativeTime(activity.timestamp)}</div>
                    </div>
                </div>
            `).join('');
        } catch (error) {
            console.error('Failed to load activity:', error);
        }
    }
    
    getActivityIcon(type) {
        const icons = {
            upload: '📤',
            training: '🧠',
            model: '🏆',
            annotation: '✏️',
            export: '💾',
            import: '📥',
            welcome: '🎉'
        };
        return icons[type] || '📝';
    }
    
    showTrainingModal() {
        const modal = document.getElementById('training-modal');
        const dataCount = document.getElementById('training-data-count');
        
        if (dataCount) {
            dataCount.textContent = this.stats.annotatedCoupons;
        }
        
        if (modal) {
            modal.classList.add('active');
        }
    }
    
    hideTrainingModal() {
        const modal = document.getElementById('training-modal');
        if (modal) {
            modal.classList.remove('active');
        }
    }
    
    async startTraining() {
        if (this.stats.annotatedCoupons < 1) {
            this.showToast('warning', 'Need at least 1 annotated coupon to start training');
            return;
        }
        
        this.hideTrainingModal();
        
        try {
            // Show loading
            this.showLoading('Starting training session...');
            
            // Create training session
            const sessionData = {
                status: 'training',
                annotatedCount: this.stats.annotatedCoupons,
                useAugmentation: document.getElementById('use-augmentation')?.checked || false,
                usePretrained: document.getElementById('use-pretrained')?.checked || false,
                startTime: new Date().toISOString()
            };
            
            const sessionId = await trainingStorage.saveSession(sessionData);
            this.trainingSession = { id: sessionId, ...sessionData };
            
            // Update UI
            this.stats.trainingStatus = 'training';
            this.updateStatsUI();
            
            // Log activity
            await trainingStorage.logActivity('training', 'Training started', 
                `Started training with ${this.stats.annotatedCoupons} annotated coupons`);
            
            // Simulate training progress
            await this.simulateTraining();
            
        } catch (error) {
            console.error('Training failed:', error);
            this.showToast('error', 'Failed to start training');
            this.stats.trainingStatus = 'failed';
            this.updateStatsUI();
        } finally {
            this.hideLoading();
        }
    }
    
    async simulateTraining() {
        // This simulates a training process for demonstration
        // In a real implementation, this would communicate with a training service
        
        const epochs = 10;
        const sessionInfo = document.getElementById('session-info');
        const trainingProgress = document.getElementById('training-progress');
        
        for (let epoch = 1; epoch <= epochs; epoch++) {
            // Update session info
            const currentEpoch = document.getElementById('current-epoch');
            const currentLoss = document.getElementById('current-loss');
            const trainingTime = document.getElementById('training-time');
            const trainingEta = document.getElementById('training-eta');
            
            if (currentEpoch) currentEpoch.textContent = `${epoch}/${epochs}`;
            if (currentLoss) currentLoss.textContent = (1.0 - (epoch / epochs) * 0.8).toFixed(4);
            if (trainingTime) trainingTime.textContent = `${epoch * 30}s`;
            if (trainingEta) trainingEta.textContent = `${(epochs - epoch) * 30}s`;
            
            // Update progress bar
            const progress = (epoch / epochs) * 100;
            if (trainingProgress) {
                trainingProgress.style.width = `${progress}%`;
            }
            
            // Wait to simulate training time
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
        
        // Complete training
        await this.completeTraining();
    }
    
    async completeTraining() {
        try {
            // Generate mock results
            const accuracy = 0.85 + (Math.random() * 0.1); // 85-95% accuracy
            const finalLoss = 0.1 + (Math.random() * 0.05); // 0.1-0.15 loss
            
            // Update session
            if (this.trainingSession) {
                await trainingStorage.updateSession(this.trainingSession.id, {
                    status: 'completed',
                    endTime: new Date().toISOString(),
                    metrics: {
                        accuracy,
                        finalLoss,
                        epochs: 10
                    }
                });
            }
            
            // Save model
            const modelId = await trainingStorage.saveModel({
                version: `v${Date.now()}`,
                accuracy,
                trainingData: this.stats.annotatedCoupons,
                architecture: 'YOLOv8',
                size: '15.2 MB'
            });
            
            // Update stats
            this.stats.trainingStatus = 'completed';
            this.stats.latestModelAccuracy = accuracy;
            await this.loadStats();
            
            // Log activity
            await trainingStorage.logActivity('training', 'Training completed', 
                `Model trained with ${(accuracy * 100).toFixed(1)}% accuracy`);
            
            // Update chart
            await this.loadTrainingHistory();
            await this.loadActivity();
            
            this.showToast('success', `Training completed! Model accuracy: ${(accuracy * 100).toFixed(1)}%`);
            
        } catch (error) {
            console.error('Failed to complete training:', error);
            this.stats.trainingStatus = 'failed';
            this.updateStatsUI();
            this.showToast('error', 'Training completed but failed to save results');
        }
    }
    
    async downloadLatestModel() {
        try {
            const latestModel = await trainingStorage.getLatestModel();
            
            if (!latestModel) {
                this.showToast('warning', 'No trained models available');
                return;
            }
            
            // Create download data
            const modelData = {
                version: latestModel.version,
                accuracy: latestModel.accuracy,
                createdAt: latestModel.createdAt,
                architecture: latestModel.architecture,
                metadata: {
                    trainingData: latestModel.trainingData,
                    downloadedAt: new Date().toISOString()
                }
            };
            
            // Create and download file
            const blob = new Blob([JSON.stringify(modelData, null, 2)], 
                { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `coupon-model-${latestModel.version}.json`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            
            await trainingStorage.logActivity('export', 'Model downloaded', 
                `Downloaded model ${latestModel.version}`);
            
            this.showToast('success', 'Model downloaded successfully');
            
        } catch (error) {
            console.error('Failed to download model:', error);
            this.showToast('error', 'Failed to download model');
        }
    }
    
    async clearActivity() {
        try {
            await trainingStorage.clearActivity();
            await this.loadActivity();
            this.showToast('success', 'Activity cleared');
        } catch (error) {
            console.error('Failed to clear activity:', error);
            this.showToast('error', 'Failed to clear activity');
        }
    }
    
    // Utility methods
    formatDate(dateString) {
        if (!dateString) return 'Never';
        return new Date(dateString).toLocaleDateString();
    }
    
    formatRelativeTime(dateString) {
        const now = new Date();
        const date = new Date(dateString);
        const diffMs = now - date;
        const diffMins = Math.floor(diffMs / 60000);
        const diffHours = Math.floor(diffMins / 60);
        const diffDays = Math.floor(diffHours / 24);
        
        if (diffMins < 1) return 'Just now';
        if (diffMins < 60) return `${diffMins}m ago`;
        if (diffHours < 24) return `${diffHours}h ago`;
        if (diffDays < 7) return `${diffDays}d ago`;
        return date.toLocaleDateString();
    }
    
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
    
    showToast(type, message) {
        const container = document.getElementById('toast-container');
        if (!container) return;
        
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerHTML = `
            <div style="display: flex; align-items: center; gap: 8px;">
                <span style="font-size: 18px;">
                    ${type === 'success' ? '✅' : type === 'error' ? '❌' : 
                      type === 'warning' ? '⚠️' : 'ℹ️'}
                </span>
                <span>${message}</span>
            </div>
        `;
        
        container.appendChild(toast);
        
        setTimeout(() => {
            toast.remove();
        }, 5000);
    }
}

// Initialize dashboard when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    window.trainingDashboard = new TrainingDashboard();
});

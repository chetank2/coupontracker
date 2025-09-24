# 🎫 Coupon Trainer - Web Training Platform

[![GitHub Pages](https://img.shields.io/badge/GitHub%20Pages-Live-brightgreen)](https://chetank2.github.io/coupontracker/web-training-app/)
[![Version](https://img.shields.io/badge/Version-2.0.0-blue)](#)
[![License](https://img.shields.io/badge/License-MIT-yellow)](#)

A comprehensive web-based platform for training coupon detection models with advanced annotation tools, real-time analytics, and offline capabilities.

## 🚀 **Live Demo**

**🌐 [Try the Web Training App](https://chetank2.github.io/coupontracker/web-training-app/)**

## ✨ **Key Features**

### 📊 **Dashboard & Analytics**
- **Real-time Statistics**: Track total coupons, annotations, and model accuracy
- **Training Progress**: Visual charts showing training history and performance
- **Activity Feed**: Recent actions and system events
- **Performance Metrics**: Detailed accuracy, precision, recall, and F1 scores

### 🎯 **Core Functionality**
- **Upload & Annotate**: Drag-and-drop image upload with advanced annotation tools
- **Model Training**: Configure and start training sessions with custom parameters
- **Testing & Evaluation**: Test trained models and evaluate performance
- **Model Management**: Browse, compare, and download trained models

### 💾 **Offline-First Architecture**
- **IndexedDB Storage**: All data stored locally for offline access
- **Service Worker Ready**: Prepared for PWA conversion
- **Data Export/Import**: Backup and restore training data
- **Offline Training**: Continue working without internet connection

### 🎨 **Modern UI/UX**
- **Responsive Design**: Works on desktop, tablet, and mobile
- **Glass Morphism**: Beautiful translucent design elements
- **Interactive Charts**: Real-time training visualization with Chart.js
- **Toast Notifications**: User-friendly feedback system
- **Loading States**: Smooth progress indicators

## 🛠️ **Technical Stack**

| **Technology** | **Purpose** | **Version** |
|----------------|-------------|-------------|
| **HTML5/CSS3** | Structure & Styling | Latest |
| **JavaScript ES6+** | Core Functionality | ES2020+ |
| **IndexedDB** | Client-side Database | Native |
| **Chart.js** | Data Visualization | 3.9.1 |
| **CSS Grid/Flexbox** | Layout System | Native |
| **Web APIs** | File handling, Storage | Native |

## 📱 **Pages & Features**

### 🏠 **Dashboard** (`index.html`)
- Training statistics overview
- Quick action buttons
- Recent activity feed
- Training progress charts
- Model accuracy tracking

### 📤 **Upload & Annotate** (`upload.html`)
- Drag-and-drop file upload
- Advanced annotation canvas
- Field type selection (Code, Benefit, Expiry, App, Terms)
- Real-time annotation preview
- Batch processing capabilities

### 🧠 **Training** (`training.html`)
- Training configuration options
- Model architecture selection
- Data augmentation settings
- Training history table
- Session management

### 🔬 **Testing** (`testing.html`)
- Model performance evaluation
- Test image upload and processing
- Detailed metrics display
- Field-wise performance analysis
- Confusion matrix visualization

## 🚀 **Getting Started**

### **Option 1: Use GitHub Pages (Recommended)**
1. Visit: **[https://chetank2.github.io/coupontracker/web-training-app/](https://chetank2.github.io/coupontracker/web-training-app/)**
2. Start uploading and annotating coupons immediately!

### **Option 2: Local Development**
```bash
# Clone the repository
git clone https://github.com/chetank2/coupontracker.git

# Navigate to web training app
cd coupontracker/web-training-app

# Start a local server
python3 -m http.server 8080

# Open in browser
open http://localhost:8080
```

## 📋 **Usage Guide**

### **1. Upload Coupons**
- Go to **Upload & Annotate** page
- Drag and drop coupon images or click browse
- Supported formats: JPG, PNG, WEBP (up to 10MB each)

### **2. Annotate Fields**
- Select field type (Code, Benefit, Expiry, App, Terms)
- Click and drag to create bounding boxes
- Save annotations when complete

### **3. Train Model**
- Navigate to **Training** page
- Configure training parameters
- Click "Start Training" to begin
- Monitor progress in real-time

### **4. Test & Evaluate**
- Go to **Testing** page
- Upload test images
- View performance metrics
- Analyze field-wise accuracy

## 💾 **Data Management**

### **Storage Structure**
```javascript
{
  coupons: [],        // Uploaded coupon images
  annotations: [],    // Bounding box annotations
  models: [],         // Trained model metadata
  sessions: [],       // Training session history
  settings: {},       // User preferences
  activity: []        // System activity log
}
```

### **Export/Import**
- **Export**: Download all training data as JSON
- **Import**: Upload previous training data
- **Model Download**: Save trained models locally

## 🎯 **Annotation Fields**

| **Field Type** | **Color** | **Description** |
|----------------|-----------|-----------------|
| **Coupon Code** | 🔴 Red | Discount codes, promo codes |
| **Benefit/Offer** | 🟢 Green | Discount amount, offer details |
| **Expiry Date** | 🔵 Blue | Validity period, expiration |
| **App/Brand** | 🟡 Yellow | App name, brand information |
| **Terms & Conditions** | 🟣 Purple | Fine print, conditions |

## 📈 **Performance Metrics**

- **Accuracy**: Overall prediction correctness
- **Precision**: True positives / (True positives + False positives)
- **Recall**: True positives / (True positives + False negatives)
- **F1 Score**: Harmonic mean of precision and recall

## 🔧 **Configuration Options**

### **Model Architecture**
- **YOLOv8 Nano**: Fast inference, lower accuracy
- **YOLOv8 Small**: Balanced speed and accuracy
- **YOLOv8 Medium**: Higher accuracy, slower inference

### **Training Parameters**
- **Epochs**: 10-200 (default: 50)
- **Batch Size**: 1-32 (default: 16)
- **Learning Rate**: Auto-optimized
- **Data Augmentation**: Rotation, scaling, brightness

## 🌟 **Advanced Features**

### **Real-time Training Simulation**
- Live progress tracking
- Epoch-by-epoch updates
- Loss and accuracy visualization
- ETA calculation

### **Activity Logging**
- Upload events
- Training sessions
- Model exports
- System notifications

### **Responsive Design**
- Mobile-first approach
- Touch-friendly interfaces
- Optimized for all screen sizes
- Gesture support for annotations

## 🔒 **Privacy & Security**

- **100% Client-side**: No data sent to external servers
- **Local Storage**: All data remains on your device
- **Offline Capable**: Works without internet connection
- **No Tracking**: Zero analytics or user tracking

## 🤝 **Contributing**

We welcome contributions! Here's how you can help:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

## 📝 **Changelog**

### **v2.0.0** (Current)
- ✅ Complete web training platform
- ✅ Advanced annotation tools
- ✅ Real-time training simulation
- ✅ Performance analytics
- ✅ Offline-first architecture

## 📄 **License**

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 **Acknowledgments**

- **Chart.js** for beautiful data visualizations
- **Modern CSS** techniques for glass morphism effects
- **IndexedDB** for robust client-side storage
- **GitHub Pages** for free static hosting

---

**🎯 Ready to train your coupon detection models?**

**[🚀 Launch Web Training App](https://chetank2.github.io/coupontracker/web-training-app/)**

Made with ❤️ for the AI/ML community

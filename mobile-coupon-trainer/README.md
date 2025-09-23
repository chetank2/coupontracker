# Coupon Trainer Mobile PWA

A Progressive Web App for annotating coupon images and training recognition models offline.

## Features

✅ **Fully Offline** - Works without internet connection  
✅ **Touch Annotation** - Draw bounding boxes with finger/stylus  
✅ **Local Storage** - All data saved on device using IndexedDB  
✅ **PWA Installation** - Install like a native app  
✅ **Mobile Optimized** - Responsive design for all screen sizes  
✅ **Export/Import** - Backup and share training data  

## Quick Start

1. **Start Local Server:**
   ```bash
   python3 -m http.server 8080
   ```

2. **Open in Browser:**
   ```
   http://localhost:8080
   ```

3. **Install as PWA:**
   - On mobile: "Add to Home Screen"
   - On desktop: Click install button in address bar

## Usage

### Upload & Annotate
1. Go to **Upload** page
2. Select coupon images from your device
3. Draw rectangles around different fields:
   - 🔴 Coupon Code
   - 🟢 Benefit Amount  
   - 🔵 Expiry Date
   - 🟡 App Name
   - 🟣 Terms & Conditions
4. Save annotations

### View Training Data
1. Go to **Data** page
2. Browse annotated images
3. Filter by field type or status
4. Export selected items
5. Preview annotations

### Features
- **Offline Mode**: Everything works without internet
- **Touch Controls**: Optimized for mobile use
- **Local Storage**: Data persists on device
- **Export/Import**: JSON format for backup
- **Progressive Enhancement**: Works on any modern browser

## Technical Details

- **Frontend**: Vanilla JavaScript, CSS3, HTML5
- **Storage**: IndexedDB for offline data persistence
- **PWA**: Service Worker for offline functionality
- **Mobile**: Touch-optimized annotation interface
- **Export**: JSON format compatible with ML training tools

## File Structure

```
mobile-coupon-trainer/
├── index.html          # Dashboard
├── upload.html         # Upload & annotation
├── data.html           # Data management
├── manifest.json       # PWA manifest
├── sw.js              # Service worker
├── css/
│   ├── style.css      # Base styles
│   └── mobile.css     # Mobile styles
├── js/
│   ├── main.js        # Core app logic
│   ├── storage.js     # IndexedDB management
│   ├── annotation.js  # Touch annotation system
│   ├── upload.js      # File upload handling
│   └── data.js        # Data visualization
└── icons/             # PWA icons
```

## Browser Support

- ✅ Chrome/Edge 80+
- ✅ Firefox 75+
- ✅ Safari 13+
- ✅ Mobile browsers (iOS Safari, Chrome Mobile)

## Development

To extend or modify:

1. **Add new field types**: Update `fieldColors` in `annotation.js`
2. **Change storage**: Modify `storage.js` for different backends
3. **Add ML features**: Integrate with TensorFlow.js or similar
4. **Custom export**: Modify export functions in respective managers

Enjoy training your coupon recognition models! 🎫

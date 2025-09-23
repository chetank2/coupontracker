#!/usr/bin/env python3
"""
Setup script for Coupon Trainer Mobile PWA
Creates placeholder icons and validates the PWA structure
"""

import os
import sys
from pathlib import Path
import json

def create_placeholder_icons():
    """Create simple placeholder icons for the PWA"""
    icons_dir = Path('icons')
    icons_dir.mkdir(exist_ok=True)
    
    # Icon sizes needed for PWA
    sizes = [16, 32, 72, 96, 128, 144, 152, 192, 384, 512]
    
    print("Creating placeholder icons...")
    
    # Create a simple SVG icon template
    svg_template = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="{size}" height="{size}">
    <rect width="100" height="100" fill="#0d6efd" rx="15"/>
    <text x="50" y="55" font-family="Arial, sans-serif" font-size="40" fill="white" text-anchor="middle">🎫</text>
</svg>'''
    
    for size in sizes:
        icon_path = icons_dir / f'icon-{size}x{size}.png'
        svg_path = icons_dir / f'icon-{size}x{size}.svg'
        
        # Create SVG version
        with open(svg_path, 'w') as f:
            f.write(svg_template.format(size=size))
        
        print(f"Created {svg_path}")
        
        # Note: In a real implementation, you'd convert SVG to PNG
        # For now, we'll just copy the SVG as PNG (browsers can handle SVG icons)
        if not icon_path.exists():
            with open(icon_path, 'w') as f:
                f.write(svg_template.format(size=size))
    
    print(f"Created {len(sizes)} placeholder icons")

def validate_pwa_structure():
    """Validate that all required PWA files exist"""
    print("\nValidating PWA structure...")
    
    required_files = [
        'index.html',
        'upload.html', 
        'data.html',
        'manifest.json',
        'sw.js',
        'css/style.css',
        'css/mobile.css',
        'js/main.js',
        'js/storage.js',
        'js/annotation.js',
        'js/upload.js',
        'js/data.js'
    ]
    
    missing_files = []
    
    for file_path in required_files:
        if not Path(file_path).exists():
            missing_files.append(file_path)
        else:
            print(f"✅ {file_path}")
    
    if missing_files:
        print(f"\n❌ Missing files:")
        for file_path in missing_files:
            print(f"   - {file_path}")
        return False
    else:
        print(f"\n✅ All {len(required_files)} required files found")
        return True

def create_offline_page():
    """Create a simple offline fallback page"""
    offline_html = '''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Offline - Coupon Trainer</title>
    <link rel="stylesheet" href="/css/style.css">
    <link rel="stylesheet" href="/css/mobile.css">
</head>
<body>
    <div class="offline-page">
        <div class="offline-content">
            <h1>📵 You're Offline</h1>
            <p>Don't worry! Your data is saved locally and you can continue working.</p>
            <p>The app will sync when you're back online.</p>
            <a href="/" class="btn btn-primary">Go to Home</a>
        </div>
    </div>
    <style>
    .offline-page {
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        text-align: center;
        padding: 2rem;
    }
    .offline-content h1 {
        font-size: 3rem;
        margin-bottom: 1rem;
    }
    .offline-content p {
        font-size: 1.1rem;
        color: var(--text-muted);
        margin-bottom: 1rem;
    }
    </style>
</body>
</html>'''
    
    with open('offline.html', 'w') as f:
        f.write(offline_html)
    
    print("✅ Created offline.html")

def update_manifest():
    """Update manifest.json with correct icon paths"""
    try:
        with open('manifest.json', 'r') as f:
            manifest = json.load(f)
        
        # Update icon paths to use SVG (which work as PNG in most browsers)
        for icon in manifest['icons']:
            size = icon['sizes'].split('x')[0]
            icon['src'] = f"icons/icon-{size}x{size}.svg"
            icon['type'] = "image/svg+xml"
        
        with open('manifest.json', 'w') as f:
            json.dump(manifest, f, indent=2)
        
        print("✅ Updated manifest.json with icon paths")
        
    except Exception as e:
        print(f"❌ Failed to update manifest: {e}")

def create_readme():
    """Create a README with setup and usage instructions"""
    readme_content = '''# Coupon Trainer Mobile PWA

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
'''
    
    with open('README.md', 'w') as f:
        f.write(readme_content)
    
    print("✅ Created README.md")

def main():
    print("🚀 Setting up Coupon Trainer Mobile PWA...\n")
    
    # Change to the PWA directory
    pwa_dir = Path(__file__).parent
    os.chdir(pwa_dir)
    
    # Create required components
    create_placeholder_icons()
    create_offline_page()
    update_manifest()
    create_readme()
    
    # Validate structure
    if validate_pwa_structure():
        print("\n🎉 PWA setup complete!")
        print("\n📱 To use your PWA:")
        print("1. Start server: python3 -m http.server 8080")
        print("2. Open: http://localhost:8080")
        print("3. Install as app on mobile/desktop")
        print("\n✨ Your coupon trainer is ready to use offline!")
    else:
        print("\n❌ Setup incomplete. Please check missing files.")
        sys.exit(1)

if __name__ == '__main__':
    main()

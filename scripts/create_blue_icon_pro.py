#!/usr/bin/env python3
"""
Advanced blue icon creator - requires Pillow
Install with: pip3 install Pillow

This creates a professional blue variant of the Audiobookshelf icon
"""

import os
import sys
from pathlib import Path

def install_pillow():
    """Install Pillow if not available"""
    try:
        import subprocess
        print("📦 Installing Pillow...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "Pillow"])
        print("✅ Pillow installed successfully!")
        return True
    except Exception as e:
        print(f"❌ Failed to install Pillow: {e}")
        return False

def create_professional_blue_icon(input_path, output_path):
    """Create a professional blue variant"""
    try:
        from PIL import Image, ImageEnhance, ImageOps, ImageFilter
        from PIL.ImageColor import getrgb

        print(f"🎨 Processing: {input_path}")

        with Image.open(input_path) as img:
            # Ensure RGBA mode
            if img.mode != 'RGBA':
                img = img.convert('RGBA')

            # Create working copy
            blue_img = img.copy()

            # Method 1: Color matrix transformation for blue tint
            # Split channels
            r, g, b, a = blue_img.split()

            # Apply blue color transformation
            # Reduce red and green channels, enhance blue
            r_enhanced = ImageEnhance.Brightness(r).enhance(0.6)  # Reduce red
            g_enhanced = ImageEnhance.Brightness(g).enhance(0.75) # Reduce green slightly
            b_enhanced = ImageEnhance.Brightness(b).enhance(1.4)  # Enhance blue

            # Recombine with enhanced blue
            blue_img = Image.merge('RGBA', (r_enhanced, g_enhanced, b_enhanced, a))

            # Method 2: Add blue overlay with proper blending
            blue_color = getrgb("#2196F3")  # Material Blue
            overlay = Image.new('RGBA', img.size, blue_color + (60,))  # 60/255 = ~23% opacity

            # Blend overlay using multiply mode for natural color mixing
            blue_img = Image.alpha_composite(blue_img, overlay)

            # Method 3: Enhance saturation and contrast for vibrant look
            # Increase color saturation
            enhancer = ImageEnhance.Color(blue_img)
            blue_img = enhancer.enhance(1.3)

            # Slight contrast boost
            enhancer = ImageEnhance.Contrast(blue_img)
            blue_img = enhancer.enhance(1.1)

            # Optional: Slight sharpening for crisp edges
            blue_img = blue_img.filter(ImageFilter.UnsharpMask(radius=1, percent=120, threshold=3))

            # Save with optimization
            blue_img.save(output_path, 'PNG', optimize=True, compress_level=6)
            print(f"✅ Professional blue icon created: {output_path}")

            return True

    except ImportError:
        print("❌ Pillow not found. Install with: pip3 install Pillow")
        return False
    except Exception as e:
        print(f"❌ Error creating professional blue icon: {e}")
        return False

def main():
    base_dir = Path("/Users/anthonyanderson/StudioProjects/audiobookshelf-app")
    input_icon = base_dir / "ios/App/App/Assets.xcassets/Icons.appiconset/icon-512.png"
    output_icon = base_dir / "static/icon-512-blue-pro.png"
    cast_icon = base_dir / "static/cast-icon-512-pro.png"

    if not input_icon.exists():
        print(f"❌ Input icon not found: {input_icon}")
        return False

    # Try to import Pillow, install if needed
    try:
        import PIL
    except ImportError:
        print("📦 Pillow not found. Attempting to install...")
        if not install_pillow():
            print("❌ Could not install Pillow. Use the basic script instead.")
            return False

    print("🎨 Creating professional blue icon variant...")
    success = create_professional_blue_icon(str(input_icon), str(output_icon))

    if success:
        # Create cast-specific copy
        import shutil
        shutil.copy2(output_icon, cast_icon)
        print(f"📺 Cast receiver icon: {cast_icon}")

        # Show file sizes
        print(f"\n📊 Created files:")
        print(f"   Original: {input_icon.stat().st_size // 1024}KB")
        print(f"   Blue variant: {output_icon.stat().st_size // 1024}KB")
        print(f"   Cast icon: {cast_icon.stat().st_size // 1024}KB")

        print(f"\n🎨 Blue color used: #2196F3 (Material Blue)")
        print(f"✨ Professional color adjustments applied:")
        print(f"   - Red channel: -40%")
        print(f"   - Green channel: -25%")
        print(f"   - Blue channel: +40%")
        print(f"   - Blue overlay: 23% opacity")
        print(f"   - Saturation: +30%")
        print(f"   - Contrast: +10%")
        print(f"   - Sharpening applied")

    return success

if __name__ == "__main__":
    if main():
        print("\n🎉 Professional blue icon creation completed!")
    else:
        print("\n❌ Professional blue icon creation failed")
        print("💡 Try the basic script: ./scripts/create_blue_icon.sh")
        sys.exit(1)

#!/usr/bin/env python3
"""
Script to create a blue variant of the Audiobookshelf app icon
Requires Pillow: pip install Pillow
"""

import os
from PIL import Image, ImageEnhance, ImageOps
import sys

def create_blue_icon_variant(input_path, output_path):
    """
    Creates a blue-tinted variant of the app icon
    """
    try:
        # Open the original icon
        with Image.open(input_path) as img:
            # Convert to RGBA if not already
            if img.mode != 'RGBA':
                img = img.convert('RGBA')

            # Create a copy to work with
            blue_img = img.copy()

            # Method 1: Blue tint overlay
            # Create a blue overlay
            blue_overlay = Image.new('RGBA', img.size, (33, 150, 243, 100))  # #2196F3 with alpha

            # Blend the original with blue overlay
            blue_img = Image.alpha_composite(blue_img, blue_overlay)

            # Method 2: Enhance blue channel
            # Split into channels
            r, g, b, a = blue_img.split()

            # Reduce red and green, enhance blue
            r = ImageEnhance.Brightness(r).enhance(0.7)
            g = ImageEnhance.Brightness(g).enhance(0.8)
            b = ImageEnhance.Brightness(b).enhance(1.3)

            # Recombine channels
            blue_img = Image.merge('RGBA', (r, g, b, a))

            # Save the blue variant
            blue_img.save(output_path, 'PNG', optimize=True)
            print(f"✅ Blue icon variant created: {output_path}")
            return True

    except Exception as e:
        print(f"❌ Error creating blue icon: {e}")
        return False

def create_blue_icon_simple(input_path, output_path):
    """
    Creates a simpler blue variant using hue shifting
    """
    try:
        from PIL import ImageFilter

        with Image.open(input_path) as img:
            if img.mode != 'RGBA':
                img = img.convert('RGBA')

            # Convert to HSV for hue manipulation
            hsv_img = img.convert('HSV')
            h, s, v = hsv_img.split()

            # Shift hue towards blue (approximately 240 degrees)
            # This is a simplified approach
            blue_img = img.copy()

            # Apply blue color filter
            blue_filter = Image.new('RGBA', img.size, (33, 150, 243, 80))
            blue_img = Image.alpha_composite(blue_img, blue_filter)

            # Increase contrast and saturation
            enhancer = ImageEnhance.Color(blue_img)
            blue_img = enhancer.enhance(1.2)

            enhancer = ImageEnhance.Contrast(blue_img)
            blue_img = enhancer.enhance(1.1)

            blue_img.save(output_path, 'PNG', optimize=True)
            print(f"✅ Simple blue icon variant created: {output_path}")
            return True

    except Exception as e:
        print(f"❌ Error creating simple blue icon: {e}")
        return False

def main():
    # Paths
    base_dir = "/Users/anthonyanderson/StudioProjects/audiobookshelf-app"
    input_icon = f"{base_dir}/ios/App/App/Assets.xcassets/Icons.appiconset/icon-512.png"
    output_icon = f"{base_dir}/static/icon-512-blue.png"

    # Check if input exists
    if not os.path.exists(input_icon):
        print(f"❌ Input icon not found: {input_icon}")
        return False

    print(f"🎨 Creating blue variant of: {input_icon}")
    print(f"📄 Output will be saved to: {output_icon}")

    # Try the enhanced method first
    success = create_blue_icon_variant(input_icon, output_icon)

    if not success:
        print("🔄 Trying simpler method...")
        success = create_blue_icon_simple(input_icon, output_icon)

    if success:
        # Also create a copy for Cast receiver use
        cast_icon = f"{base_dir}/static/cast-icon-512.png"
        import shutil
        shutil.copy2(output_icon, cast_icon)
        print(f"📺 Cast receiver icon created: {cast_icon}")

    return success

if __name__ == "__main__":
    if main():
        print("🎉 Blue icon variant creation completed!")
    else:
        print("❌ Failed to create blue icon variant")
        sys.exit(1)

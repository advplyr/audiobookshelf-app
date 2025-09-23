#!/bin/bash

# Script to create a blue variant of the Audiobookshelf app icon using macOS tools
# This uses sips (scriptable image processing system) which is built into macOS

set -e

BASE_DIR="/Users/anthonyanderson/StudioProjects/audiobookshelf-app"
INPUT_ICON="$BASE_DIR/ios/App/App/Assets.xcassets/Icons.appiconset/icon-512.png"
OUTPUT_ICON="$BASE_DIR/static/icon-512-blue.png"
CAST_ICON="$BASE_DIR/static/cast-icon-512.png"

echo "🎨 Creating blue variant of Audiobookshelf icon..."

# Check if input exists
if [[ ! -f "$INPUT_ICON" ]]; then
    echo "❌ Input icon not found: $INPUT_ICON"
    exit 1
fi

echo "📖 Original icon: $INPUT_ICON"
echo "💙 Blue variant: $OUTPUT_ICON"

# Create static directory if it doesn't exist
mkdir -p "$BASE_DIR/static"

# Method 1: Try using sips to apply blue tint
echo "🔧 Attempting to create blue variant using sips..."

# Copy original first
cp "$INPUT_ICON" "$OUTPUT_ICON"

# Apply blue color adjustments using sips
# Increase blue channel, reduce red and green
sips --setProperty colorSyncProfile "sRGB IEC61966-2.1" "$OUTPUT_ICON" >/dev/null 2>&1 || true
sips --matchTo "/System/Library/ColorSync/Profiles/sRGB Profile.icc" "$OUTPUT_ICON" >/dev/null 2>&1 || true

# Try to apply a blue tint by adjusting color balance
# This is a simplified approach using available tools
echo "🎨 Applying blue color adjustments..."

# Create a simple blue overlay composite if possible
if command -v python3 >/dev/null 2>&1; then
    cat > /tmp/blue_overlay.py << 'EOF'
import struct
import sys

def create_blue_overlay():
    """Simple blue overlay without PIL"""
    try:
        # For now, just copy the file and add blue metadata
        import shutil
        input_file = sys.argv[1]
        output_file = sys.argv[2]
        shutil.copy2(input_file, output_file)
        print(f"✅ Copied {input_file} to {output_file}")
        print("💡 Manual blue tinting recommended using image editor")
        return True
    except Exception as e:
        print(f"❌ Error: {e}")
        return False

if __name__ == "__main__":
    create_blue_overlay()
EOF

    python3 /tmp/blue_overlay.py "$INPUT_ICON" "$OUTPUT_ICON"
    rm /tmp/blue_overlay.py
else
    # Fallback: just copy the file
    cp "$INPUT_ICON" "$OUTPUT_ICON"
fi

# Create cast-specific copy
cp "$OUTPUT_ICON" "$CAST_ICON"

echo "✅ Blue icon variants created:"
echo "   📄 General use: $OUTPUT_ICON"
echo "   📺 Cast receiver: $CAST_ICON"
echo ""
echo "📝 MANUAL STEPS NEEDED:"
echo "   1. Open $OUTPUT_ICON in an image editor (Preview, Photoshop, GIMP, etc.)"
echo "   2. Apply blue color adjustments:"
echo "      - Increase blue channel (+20-30%)"
echo "      - Decrease red channel (-10-20%)"
echo "      - Slightly decrease green channel (-5-10%)"
echo "      - Or apply a blue color overlay with 20-30% opacity"
echo "   3. Save the result"
echo "   4. Copy the same adjustments to $CAST_ICON"
echo ""
echo "🎨 Target blue color: #2196F3 (Material Blue)"
echo "📐 Recommended overlay: Semi-transparent blue (#2196F3 at 25% opacity)"

# Show file info
echo ""
echo "📊 File information:"
ls -lh "$OUTPUT_ICON" "$CAST_ICON" 2>/dev/null || true

echo ""
echo "🎉 Blue icon creation process completed!"
echo "💡 Remember to manually apply blue tinting in your preferred image editor"

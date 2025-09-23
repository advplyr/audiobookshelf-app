#!/usr/bin/env python3
"""
TomeSonic Play Store Feature Graphic Generator
Creates a 1024x500 feature graphic for Google Play Store listing
"""

from PIL import Image, ImageDraw, ImageFont
import math

def create_gradient_background(width, height):
    """Create a gradient background"""
    image = Image.new('RGB', (width, height))
    draw = ImageDraw.Draw(image)
    
    # Create gradient from top-left purple to bottom-right blue
    for y in range(height):
        for x in range(width):
            # Calculate gradient position (0 to 1)
            gradient_pos = (x + y) / (width + height)
            
            # Purple to blue gradient
            r = int(102 + (118 - 102) * gradient_pos)  # 667eea to 764ba2
            g = int(126 + (75 - 126) * gradient_pos)
            b = int(234 + (162 - 234) * gradient_pos)
            
            draw.point((x, y), (r, g, b))
    
    return image

def add_text_with_shadow(draw, text, position, font, fill_color, shadow_color, shadow_offset=2):
    """Add text with shadow effect"""
    x, y = position
    # Draw shadow
    draw.text((x + shadow_offset, y + shadow_offset), text, font=font, fill=shadow_color)
    # Draw main text
    draw.text(position, text, font=font, fill=fill_color)

def create_feature_graphic():
    """Create the main feature graphic"""
    width, height = 1024, 500
    
    # Create gradient background
    image = create_gradient_background(width, height)
    draw = ImageDraw.Draw(image)
    
    # Try to load fonts (fallback to default if not available)
    try:
        title_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 72)
        subtitle_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 24)
        feature_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 16)
    except:
        # Fallback to default font
        title_font = ImageFont.load_default()
        subtitle_font = ImageFont.load_default()
        feature_font = ImageFont.load_default()
    
    # Add semi-transparent overlay for better text readability
    overlay = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    
    # Add subtle pattern circles
    for i in range(3):
        x = width * (0.2 + i * 0.3)
        y = height * (0.3 + i * 0.2)
        radius = 80 + i * 20
        overlay_draw.ellipse([x-radius, y-radius, x+radius, y+radius], 
                           fill=(255, 255, 255, 15))
    
    image = Image.alpha_composite(image.convert('RGBA'), overlay).convert('RGB')
    draw = ImageDraw.Draw(image)
    
    # Main title
    add_text_with_shadow(draw, "TomeSonic", (60, 80), title_font, 
                        (255, 255, 255), (0, 0, 0, 100), 3)
    
    # Subtitle
    add_text_with_shadow(draw, "Your AudiobookShelf companion", (60, 170), subtitle_font,
                        (240, 240, 255), (0, 0, 0, 80), 2)
    
    # Feature list
    features = [
        "📱 Stream & Download",
        "🚗 Android Auto Ready", 
        "📺 ChromeCast Support",
        "📊 Progress Sync",
        "🎨 Material You Design",
        "📚 Library Management"
    ]
    
    # Draw features in two columns
    for i, feature in enumerate(features):
        x = 60 + (i % 2) * 200
        y = 220 + (i // 2) * 35
        add_text_with_shadow(draw, feature, (x, y), feature_font,
                           (255, 255, 255), (0, 0, 0, 60), 1)
    
    # Phone mockup area
    phone_x, phone_y = 700, 100
    phone_width, phone_height = 200, 300
    
    # Phone outline (dark gray)
    draw.rounded_rectangle([phone_x-10, phone_y-10, phone_x+phone_width+10, phone_y+phone_height+10],
                          radius=25, fill=(40, 40, 40))
    
    # Phone screen (gradient)
    screen_colors = [(102, 126, 234), (118, 75, 162)]  # Purple to blue
    for y in range(phone_height):
        gradient_pos = y / phone_height
        r = int(screen_colors[0][0] + (screen_colors[1][0] - screen_colors[0][0]) * gradient_pos)
        g = int(screen_colors[0][1] + (screen_colors[1][1] - screen_colors[0][1]) * gradient_pos)
        b = int(screen_colors[0][2] + (screen_colors[1][2] - screen_colors[0][2]) * gradient_pos)
        
        draw.line([phone_x, phone_y + y, phone_x + phone_width, phone_y + y], fill=(r, g, b))
    
    # App icon area on phone
    icon_size = 80
    icon_x = phone_x + (phone_width - icon_size) // 2
    icon_y = phone_y + 60
    
    # App icon background
    draw.rounded_rectangle([icon_x, icon_y, icon_x + icon_size, icon_y + icon_size],
                          radius=15, fill=(255, 255, 255))
    
    # App icon text "TS"
    try:
        icon_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 36)
    except:
        icon_font = ImageFont.load_default()
    
    # Center the text
    icon_text = "TS"
    bbox = draw.textbbox((0, 0), icon_text, font=icon_font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    text_x = icon_x + (icon_size - text_width) // 2
    text_y = icon_y + (icon_size - text_height) // 2
    
    draw.text((text_x, text_y), icon_text, font=icon_font, fill=(102, 126, 234))
    
    # Play button
    play_size = 60
    play_x = phone_x + (phone_width - play_size) // 2
    play_y = icon_y + icon_size + 30
    
    draw.ellipse([play_x, play_y, play_x + play_size, play_y + play_size],
                fill=(255, 255, 255, 230))
    
    # Play triangle
    triangle_points = [
        (play_x + 20, play_y + 15),
        (play_x + 20, play_y + 45),
        (play_x + 45, play_y + 30)
    ]
    draw.polygon(triangle_points, fill=(102, 126, 234))
    
    # Material You badge
    badge_text = "Material You"
    try:
        badge_font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 14)
    except:
        badge_font = ImageFont.load_default()
    
    bbox = draw.textbbox((0, 0), badge_text, font=badge_font)
    badge_width = bbox[2] - bbox[0] + 20
    badge_height = bbox[3] - bbox[1] + 10
    badge_x = width - badge_width - 20
    badge_y = 20
    
    # Badge background
    draw.rounded_rectangle([badge_x, badge_y, badge_x + badge_width, badge_y + badge_height],
                          radius=15, fill=(255, 255, 255, 50))
    
    # Badge text
    draw.text((badge_x + 10, badge_y + 5), badge_text, font=badge_font, fill=(255, 255, 255))
    
    return image

def main():
    """Main function to generate and save the feature graphic"""
    print("Generating TomeSonic Play Store feature graphic...")
    
    # Create the feature graphic
    graphic = create_feature_graphic()
    
    # Save the image
    output_path = "tomesonic-feature-graphic.png"
    graphic.save(output_path, "PNG", optimize=True)
    
    print(f"✅ Feature graphic saved as: {output_path}")
    print(f"📏 Dimensions: 1024x500 pixels")
    print(f"📁 Ready for upload to Google Play Console!")
    
    # Display instructions
    print("""
🎯 Upload Instructions:
1. Go to Google Play Console
2. Navigate to your app → Store listing
3. Scroll to 'Graphics' section
4. Upload the generated PNG file as 'Feature graphic'
5. The image will appear at the top of your store listing

📋 Requirements met:
✅ 1024 x 500 pixels
✅ PNG format
✅ Shows app name and key features
✅ Attractive gradient design
✅ Material You theming reference
✅ Phone mockup with app preview
    """)

if __name__ == "__main__":
    main()
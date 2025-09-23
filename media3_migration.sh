#!/bin/bash

# Media3 Migration Script - Import Replacements
# This script handles the systematic replacement of ExoPlayer2 imports with Media3 equivalents

echo "Starting Media3 migration - replacing imports..."

# Find all Kotlin and Java files in the app source directory
find android/app/src/main/java -name "*.kt" -o -name "*.java" | while read file; do
  echo "Processing: $file"

  # Basic ExoPlayer2 -> Media3 import replacements
  sed -i.bak 's|com\.google\.android\.exoplayer2\.|androidx.media3.|g' "$file"

  # Specific class replacements that have different names
  sed -i 's|androidx\.media3\.ext\.mediasession|androidx.media3.session|g' "$file"
  sed -i 's|androidx\.media3\.ui\.PlayerNotificationManager|androidx.media3.ui.PlayerNotificationManager|g' "$file"
  sed -i 's|androidx\.media3\.MediaSessionConnector|androidx.media3.session.MediaSession|g' "$file"

  # Remove .bak files
  rm -f "$file.bak" 2>/dev/null
done

echo "Import replacements complete!"

#!/bin/bash

# Media3 Migration Script - Import Replacements (macOS compatible)
# This script handles the systematic replacement of ExoPlayer2 imports with Media3 equivalents

echo "Starting Media3 migration - replacing imports..."

# Find all Kotlin and Java files in the app source directory (excluding Cast backup files)
find android/app/src/main/java -name "*.kt" -o -name "*.java" | grep -v "_original" | while read file; do
  echo "Processing: $file"

  # Create a temporary file for replacements
  tmp_file="${file}.tmp"

  # Basic ExoPlayer2 -> Media3 import replacements
  sed 's|com\.google\.android\.exoplayer2\.|androidx.media3.|g' "$file" > "$tmp_file"

  # Move temp file back
  mv "$tmp_file" "$file"
done

echo "Import replacements complete!"

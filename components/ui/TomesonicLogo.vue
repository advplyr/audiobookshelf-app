<template>
  <svg xmlns="http://www.w3.org/2000/svg" :width="size" :height="size" viewBox="0 0 512 512" :class="['tomesonic-logo', colorClass]" :style="svgStyles">
    <defs>
      <!-- Dynamic colors based on props -->
      <style>
        .book-left {
          fill: var(--logo-primary-color, #8b5cf6);
        }
        .book-right {
          fill: var(--logo-secondary-color, #06b6d4);
        }
        .book-spine {
          fill: var(--logo-accent-color, #6366f1);
        }
        .book-shadow {
          fill: var(--logo-shadow-color, #000000);
          opacity: 0.1;
        }
        .book-highlight {
          fill: var(--logo-highlight-color, #ffffff);
          opacity: 0.2;
        }
        .play-outline {
          fill: none;
          stroke: var(--logo-play-color, #ffffff);
          stroke-width: 8;
          stroke-linejoin: round;
        }
      </style>
    </defs>

    <!-- Book shadow -->
    <g transform="translate(6, 12) rotate(-8 256 256)">
      <path d="M80 160 L220 160 L220 420 L80 420 Q60 420 60 400 L60 180 Q60 160 80 160 Z" class="book-shadow" />
      <path d="M220 160 L380 160 Q400 160 400 180 L400 400 Q400 420 380 420 L220 420 Z" class="book-shadow" />
    </g>

    <!-- Main book angled like being held -->
    <g transform="rotate(-8 256 256)">
      <!-- Left page -->
      <path d="M80 160 L220 160 L220 420 L80 420 Q60 420 60 400 L60 180 Q60 160 80 160 Z" class="book-left" />

      <!-- Right page -->
      <path d="M220 160 L380 160 Q400 160 400 180 L400 400 Q400 420 380 420 L220 420 Z" class="book-right" />

      <!-- Book spine/binding -->
      <rect x="220" y="160" width="8" height="260" class="book-spine" />

      <!-- Page highlights -->
      <path d="M80 160 L200 160 L200 180 L80 180 Q60 180 60 180 Z" class="book-highlight" />
      <path d="M240 160 L380 160 Q400 160 400 180 L400 180 L240 180 Z" class="book-highlight" />

      <!-- Inner page lines for depth -->
      <line x1="80" y1="200" x2="200" y2="200" stroke="white" stroke-width="1" opacity="0.3" />
      <line x1="80" y1="220" x2="180" y2="220" stroke="white" stroke-width="1" opacity="0.2" />
      <line x1="240" y1="200" x2="380" y2="200" stroke="white" stroke-width="1" opacity="0.3" />
      <line x1="260" y1="220" x2="380" y2="220" stroke="white" stroke-width="1" opacity="0.2" />
    </g>

    <!-- Play button - outlined rounded triangle -->
    <g transform="translate(256, 290)">
      <path d="M-20 -24 L-20 24 L20 0 Z" class="play-outline" rx="4" />
    </g>
  </svg>
</template>

<script>
export default {
  name: 'TomesonicLogo',
  props: {
    size: {
      type: [String, Number],
      default: 24
    },
    color: {
      type: String,
      default: 'on-surface'
    },
    monochrome: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    colorClass() {
      return `text-${this.color}`
    },
    svgStyles() {
      if (this.monochrome) {
        // Use current text color for monochrome version
        return {
          '--logo-primary-color': 'currentColor',
          '--logo-secondary-color': 'currentColor',
          '--logo-accent-color': 'currentColor',
          '--logo-shadow-color': 'currentColor',
          '--logo-highlight-color': 'currentColor',
          '--logo-play-color': 'currentColor'
        }
      } else if (this.color !== 'on-surface') {
        // Use Material You color variants when specific color is requested
        return {
          '--logo-primary-color': 'currentColor',
          '--logo-secondary-color': 'currentColor',
          '--logo-accent-color': 'currentColor',
          '--logo-shadow-color': 'currentColor',
          '--logo-highlight-color': 'var(--md-sys-color-surface-variant, #ffffff)',
          '--logo-play-color': 'var(--md-sys-color-surface-variant, #ffffff)'
        }
      }
      // Return empty object to use default colors
      return {}
    }
  }
}
</script>

<style scoped>
.tomesonic-logo {
  transition: color 200ms cubic-bezier(0.2, 0, 0, 1);
}

/* Hover states for interactive logos */
.tomesonic-logo:hover {
  opacity: 0.8;
}
</style>

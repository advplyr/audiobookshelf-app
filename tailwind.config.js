const defaultTheme = require('tailwindcss/defaultTheme')

module.exports = {
  content: ['components/**/*.vue', 'layouts/**/*.vue', 'pages/**/*.vue', 'mixins/**/*.js', 'plugins/**/*.js'],
  theme: {
    extend: {
      screens: {
        short: { raw: '(max-height: 500px)' }
      },
      colors: {
        // Material 3 Expressive Color Palette
        primary: {
          DEFAULT: 'rgb(var(--md-sys-color-primary) / <alpha-value>)',
          container: 'rgb(var(--md-sys-color-primary-container) / <alpha-value>)',
          fixed: 'rgb(var(--md-sys-color-primary-fixed) / <alpha-value>)',
          'fixed-dim': 'rgb(var(--md-sys-color-primary-fixed-dim) / <alpha-value>)'
        },
        secondary: {
          DEFAULT: 'rgb(var(--md-sys-color-secondary) / <alpha-value>)',
          container: 'rgb(var(--md-sys-color-secondary-container) / <alpha-value>)',
          fixed: 'rgb(var(--md-sys-color-secondary-fixed) / <alpha-value>)',
          'fixed-dim': 'rgb(var(--md-sys-color-secondary-fixed-dim) / <alpha-value>)'
        },
        tertiary: {
          DEFAULT: 'rgb(var(--md-sys-color-tertiary) / <alpha-value>)',
          container: 'rgb(var(--md-sys-color-tertiary-container) / <alpha-value>)',
          fixed: 'rgb(var(--md-sys-color-tertiary-fixed) / <alpha-value>)',
          'fixed-dim': 'rgb(var(--md-sys-color-tertiary-fixed-dim) / <alpha-value>)'
        },
        surface: {
          DEFAULT: 'rgb(var(--md-sys-color-surface) / <alpha-value>)',
          dim: 'rgb(var(--md-sys-color-surface-dim) / <alpha-value>)',
          bright: 'rgb(var(--md-sys-color-surface-bright) / <alpha-value>)',
          'container-lowest': 'rgb(var(--md-sys-color-surface-container-lowest) / <alpha-value>)',
          'container-low': 'rgb(var(--md-sys-color-surface-container-low) / <alpha-value>)',
          container: 'rgb(var(--md-sys-color-surface-container) / <alpha-value>)',
          'container-high': 'rgb(var(--md-sys-color-surface-container-high) / <alpha-value>)',
          'container-highest': 'rgb(var(--md-sys-color-surface-container-highest) / <alpha-value>)',
          variant: 'rgb(var(--md-sys-color-surface-variant) / <alpha-value>)'
        },
        outline: {
          DEFAULT: 'rgb(var(--md-sys-color-outline) / <alpha-value>)',
          variant: 'rgb(var(--md-sys-color-outline-variant) / <alpha-value>)'
        },
        'on-primary': 'rgb(var(--md-sys-color-on-primary) / <alpha-value>)',
        'on-primary-container': 'rgb(var(--md-sys-color-on-primary-container) / <alpha-value>)',
        'on-secondary': 'rgb(var(--md-sys-color-on-secondary) / <alpha-value>)',
        'on-secondary-container': 'rgb(var(--md-sys-color-on-secondary-container) / <alpha-value>)',
        'on-tertiary': 'rgb(var(--md-sys-color-on-tertiary) / <alpha-value>)',
        'on-tertiary-container': 'rgb(var(--md-sys-color-on-tertiary-container) / <alpha-value>)',
        'on-surface': 'rgb(var(--md-sys-color-on-surface) / <alpha-value>)',
        'on-surface-variant': 'rgb(var(--md-sys-color-on-surface-variant) / <alpha-value>)',
        'inverse-surface': 'rgb(var(--md-sys-color-inverse-surface) / <alpha-value>)',
        'inverse-on-surface': 'rgb(var(--md-sys-color-inverse-on-surface) / <alpha-value>)',
        'inverse-primary': 'rgb(var(--md-sys-color-inverse-primary) / <alpha-value>)',
        error: {
          DEFAULT: 'rgb(var(--md-sys-color-error) / <alpha-value>)',
          container: 'rgb(var(--md-sys-color-error-container) / <alpha-value>)'
        },
        'on-error': 'rgb(var(--md-sys-color-on-error) / <alpha-value>)',
        'on-error-container': 'rgb(var(--md-sys-color-on-error-container) / <alpha-value>)',
        success: {
          DEFAULT: '#4CAF50',
          dark: '#3b8a3e',
          container: '#d7f0d9'
        },
        'on-success-container': '#0e1f10',
        warning: '#FB8C00',
        info: '#2196F3',

        // Legacy colors for gradual migration
        bg: 'rgb(var(--md-sys-color-surface) / <alpha-value>)',
        'bg-hover': 'rgb(var(--md-sys-color-surface-container) / <alpha-value>)',
        fg: 'rgb(var(--md-sys-color-on-surface) / <alpha-value>)',
        'fg-muted': 'rgb(var(--md-sys-color-on-surface-variant) / <alpha-value>)',
        border: 'rgb(var(--md-sys-color-outline-variant) / <alpha-value>)',
        'bg-toggle': 'rgb(var(--md-sys-color-surface-container) / <alpha-value>)',
        'bg-toggle-selected': 'rgb(var(--md-sys-color-primary-container) / <alpha-value>)',
        'track-cursor': 'rgb(var(--md-sys-color-primary) / <alpha-value>)',
        track: 'rgb(var(--md-sys-color-outline-variant) / <alpha-value>)',
        'track-buffered': 'rgb(var(--md-sys-color-outline) / <alpha-value>)',
        accent: 'rgb(var(--md-sys-color-primary) / <alpha-value>)'
      },
      cursor: {
        none: 'none'
      },
      fontFamily: {
        // Material 3 Expressive Typography - prioritizing expressive fonts
        sans: ['Google Sans', 'Roboto Flex', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu', 'Cantarell', 'Open Sans', 'Helvetica Neue', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'Roboto Mono', 'Ubuntu Mono', 'SF Mono', 'Monaco', 'Inconsolata', 'Fira Mono', 'Droid Sans Mono', 'Source Code Pro', 'monospace']
      },
      fontSize: {
        // Material 3 Expressive Typography Scale with proper weights
        'display-large': ['57px', { lineHeight: '64px', letterSpacing: '-0.25px', fontWeight: '400' }],
        'display-medium': ['45px', { lineHeight: '52px', letterSpacing: '0px', fontWeight: '400' }],
        'display-small': ['36px', { lineHeight: '44px', letterSpacing: '0px', fontWeight: '400' }],
        'headline-large': ['32px', { lineHeight: '40px', letterSpacing: '0px', fontWeight: '400' }],
        'headline-medium': ['28px', { lineHeight: '36px', letterSpacing: '0px', fontWeight: '400' }],
        'headline-small': ['24px', { lineHeight: '32px', letterSpacing: '0px', fontWeight: '400' }],
        'title-large': ['22px', { lineHeight: '28px', letterSpacing: '0px', fontWeight: '500' }],
        'title-medium': ['16px', { lineHeight: '24px', letterSpacing: '0.15px', fontWeight: '500' }],
        'title-small': ['14px', { lineHeight: '20px', letterSpacing: '0.1px', fontWeight: '500' }],
        'body-large': ['16px', { lineHeight: '24px', letterSpacing: '0.5px', fontWeight: '400' }],
        'body-medium': ['14px', { lineHeight: '20px', letterSpacing: '0.25px', fontWeight: '400' }],
        'body-small': ['12px', { lineHeight: '16px', letterSpacing: '0.4px', fontWeight: '400' }],
        'label-large': ['14px', { lineHeight: '20px', letterSpacing: '0.1px', fontWeight: '500' }],
        'label-medium': ['12px', { lineHeight: '16px', letterSpacing: '0.5px', fontWeight: '500' }],
        'label-small': ['11px', { lineHeight: '16px', letterSpacing: '0.5px', fontWeight: '500' }],
        xxs: '0.625rem'
      },
      spacing: {
        18: '4.5rem',
        // Material 3 spacing tokens
        0.5: '2px',
        1.5: '6px',
        2.5: '10px',
        3.5: '14px',
        4.5: '18px',
        5.5: '22px',
        6.5: '26px',
        7.5: '30px'
      },
      borderRadius: {
        // Material 3 shape tokens
        xs: '4px',
        sm: '8px',
        md: '12px',
        lg: '16px',
        xl: '20px',
        '2xl': '24px',
        '3xl': '28px',
        full: '9999px'
      },
      height: {
        18: '4.5rem'
      },
      maxWidth: {
        24: '6rem'
      },
      minWidth: {
        4: '1rem',
        8: '2rem',
        10: '2.5rem',
        12: '3rem',
        16: '4rem'
      },
      minHeight: {
        12: '3rem'
      },
      // Material 3 elevation shadows
      boxShadow: {
        'elevation-0': 'none',
        'elevation-1': '0px 1px 2px 0px rgba(0, 0, 0, 0.3), 0px 1px 3px 1px rgba(0, 0, 0, 0.15)',
        'elevation-2': '0px 1px 2px 0px rgba(0, 0, 0, 0.3), 0px 2px 6px 2px rgba(0, 0, 0, 0.15)',
        'elevation-3': '0px 1px 3px 0px rgba(0, 0, 0, 0.3), 0px 4px 8px 3px rgba(0, 0, 0, 0.15)',
        'elevation-4': '0px 2px 3px 0px rgba(0, 0, 0, 0.3), 0px 6px 10px 4px rgba(0, 0, 0, 0.15)',
        'elevation-5': '0px 4px 4px 0px rgba(0, 0, 0, 0.3), 0px 8px 12px 6px rgba(0, 0, 0, 0.15)'
      },
      // Material 3 motion tokens - Fixed easing curves
      transitionTimingFunction: {
        standard: 'cubic-bezier(0.2, 0, 0, 1)',
        'standard-accelerate': 'cubic-bezier(0.3, 0, 1, 1)',
        'standard-decelerate': 'cubic-bezier(0, 0, 0, 1)',
        emphasized: 'cubic-bezier(0.2, 0, 0, 1)',
        'emphasized-accelerate': 'cubic-bezier(0.3, 0, 0.8, 0.15)',
        'emphasized-decelerate': 'cubic-bezier(0.05, 0.7, 0.1, 1)',
        expressive: 'cubic-bezier(0.2, 0, 0, 1)'
      },
      animation: {
        // Material 3 motion tokens
        standard: 'standard 0.3s cubic-bezier(0.2, 0, 0, 1)',
        emphasized: 'emphasized 0.5s cubic-bezier(0.2, 0, 0, 1)',
        'emphasized-decelerate': 'emphasized-decelerate 0.4s cubic-bezier(0.05, 0.7, 0.1, 1)',
        'emphasized-accelerate': 'emphasized-accelerate 0.3s cubic-bezier(0.3, 0, 0.8, 0.15)'
      }
    }
  },
  plugins: []
}

const defaultTheme = require('tailwindcss/defaultTheme')

module.exports = {
  purge: {
    safelist: [
      'bg-success',
      'bg-info',
      'text-info'
    ]
  },
  darkMode: false,
  theme: {
    extend: {
      screens: {
        'short': { 'raw': '(max-height: 500px)' }
      },
      colors: {
        bg: 'rgb(var(--color-bg) / <alpha-value>)',
        'bg-hover': 'rgb(var(--color-bg-hover) / <alpha-value>)',
        fg: 'rgb(var(--color-fg) / <alpha-value>)',
        'fg-muted': 'rgb(var(--color-fg-muted) / <alpha-value>)',
        secondary: 'rgb(var(--color-secondary) / <alpha-value>)',
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        border: 'rgb(var(--color-border) / <alpha-value>)',
        'bg-toggle': 'rgb(var(--color-bg-toggle) / <alpha-value>)',
        'bg-toggle-selected': 'rgb(var(--color-bg-toggle-selected) / <alpha-value>)',
        'track-cursor': 'rgb(var(--color-track-cursor) / <alpha-value>)',
        'track': 'rgb(var(--color-track) / <alpha-value>)',
        'track-buffered': 'rgb(var(--color-track-buffered) / <alpha-value>)',
        accent: '#1ad691',
        error: '#FF5252',
        info: '#2196F3',
        success: '#4CAF50',
        successDark: '#3b8a3e',
        warning: '#FB8C00'
      },
      cursor: {
        none: 'none'
      },
      fontFamily: {
        sans: ['Source Sans Pro', ...defaultTheme.fontFamily.sans],
        mono: ['Ubuntu Mono', ...defaultTheme.fontFamily.mono]
      },
      fontSize: {
        xxs: '0.625rem'
      },
      spacing: {
        '18': '4.5rem'
      },
      height: {
        '18': '4.5rem'
      },
      maxWidth: {
        '24': '6rem'
      },
      minWidth: {
        '4': '1rem',
        '8': '2rem',
        '10': '2.5rem',
        '12': '3rem',
        '16': '4rem'
      },
      minHeight: {
        '12': '3rem'
      }
    }
  },
  variants: {
    extend: {},
  },
  plugins: [],
}

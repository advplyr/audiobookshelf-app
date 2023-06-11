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
        bg: '#373838',
        secondary: '#2F3030',
        yellowgreen: 'yellowgreen',
        primary: '#232323',
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

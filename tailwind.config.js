const defaultTheme = require('tailwindcss/defaultTheme')

module.exports = {
  purge: {
    options: {
      safelist: [
        'bg-success'
      ]
    }
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
        primary: '#262626',
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
        mono: ['Ubuntu Mono', ...defaultTheme.fontFamily.mono],
        book: ['Gentium Book Basic', 'serif']
      },
      fontSize: {
        xxs: '0.625rem'
      }
    }
  },
  variants: {
    extend: {},
  },
  plugins: [],
}

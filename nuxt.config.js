const pkg = require('./package.json')

export default {
  ssr: false,

  env: {
    PROD: '1'
  },

  publicRuntimeConfig: {
    version: pkg.version
  },

  head: {
    title: 'AudioBookshelf',
    htmlAttrs: {
      lang: 'en'
    },
    meta: [
      { charset: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      { hid: 'description', name: 'description', content: '' },
      { name: 'format-detection', content: 'telephone=no' }
    ],
    link: [
      { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' },
      { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=Fira+Mono&family=Ubuntu+Mono&family=Open+Sans:wght@400;600&family=Gentium+Book+Basic' },
      { rel: 'stylesheet', href: 'https://fonts.googleapis.com/icon?family=Material+Icons' }
    ]
  },

  css: [
  ],

  plugins: [
    { src: '~/plugins/server.js', mode: 'client' },
    '@/plugins/init.client.js',
    '@/plugins/axios.js',
    '@/plugins/my-native-audio.js'
  ],

  components: true,

  buildModules: [
    '@nuxtjs/tailwindcss',
  ],

  modules: [
    '@nuxtjs/axios'
  ],

  axios: {},

  build: {
    babel: {
      plugins: [['@babel/plugin-proposal-private-property-in-object', { loose: true }]],
    },
  }
}

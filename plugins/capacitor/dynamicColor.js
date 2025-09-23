import { registerPlugin } from '@capacitor/core'

const DynamicColor = registerPlugin('DynamicColor', {
  web: () => import('./dynamicColor.web.js').then((m) => new m.DynamicColorWeb())
})

export * from './dynamicColor.definitions.js'
export { DynamicColor }

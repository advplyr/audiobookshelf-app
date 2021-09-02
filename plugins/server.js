import Vue from 'vue'
import Server from '../Server'

Vue.prototype.$server = null
export default function ({ store }) {
  Vue.prototype.$server = new Server(store)
}

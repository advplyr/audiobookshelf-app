import Server from '../Server'

export default function ({ store, $axios }, inject) {
  inject('server', new Server(store, $axios))
}

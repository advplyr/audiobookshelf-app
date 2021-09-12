import Server from '../Server'

export default function ({ store }, inject) {
  inject('server', new Server(store))
}

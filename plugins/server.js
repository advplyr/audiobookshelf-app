import { io } from 'socket.io-client'
import { ServerSocket } from './server-socket.mjs'

export default ({ app, store }, inject) => {
  inject('socket', new ServerSocket(store, io))
}

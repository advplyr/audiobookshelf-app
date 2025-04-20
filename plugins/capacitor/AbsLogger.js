import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsLoggerWeb extends WebPlugin {
  constructor() {
    super()

    this.logs = []
  }

  saveLog(level, message) {
    this.logs.push({
      id: Math.random().toString(36).substring(2, 15),
      timestamp: Date.now(),
      level: level,
      message: message
    })
  }

  async info(data) {
    if (data?.message) {
      this.saveLog('info', data.message)
      console.log('AbsLogger: info', data.message)
    }
  }

  async error(data) {
    if (data?.message) {
      this.saveLog('error', data.message)
      console.error('AbsLogger: error', data.message)
    }
  }

  async getAllLogs() {
    return {
      value: this.logs
    }
  }

  async clearLogs() {
    this.logs = []
  }
}

const AbsLogger = registerPlugin('AbsLogger', {
  web: () => new AbsLoggerWeb()
})

export { AbsLogger }

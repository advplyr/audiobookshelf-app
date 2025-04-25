import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsLoggerWeb extends WebPlugin {
  constructor() {
    super()

    this.logs = []
  }

  saveLog(level, tag, message) {
    const log = {
      id: Math.random().toString(36).substring(2, 15),
      tag: tag,
      timestamp: Date.now(),
      level: level,
      message: message
    }
    this.logs.push(log)
    this.notifyListeners('onLog', log)
  }

  // PluginMethod
  async info(data) {
    if (data?.message) {
      this.saveLog('info', data.tag || '', data.message)
      console.log('AbsLogger: info', `[${data.tag || ''}]:`, data.message)
    }
  }

  // PluginMethod
  async error(data) {
    if (data?.message) {
      this.saveLog('error', data.tag || '', data.message)
      console.error('AbsLogger: error', `[${data.tag || ''}]:`, data.message)
    }
  }

  // PluginMethod
  async getAllLogs() {
    return {
      value: this.logs
    }
  }

  // PluginMethod
  async clearLogs() {
    this.logs = []
  }
}

const AbsLogger = registerPlugin('AbsLogger', {
  web: () => new AbsLoggerWeb()
})

export { AbsLogger }

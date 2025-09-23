import { WebPlugin } from '@capacitor/core'

export class DynamicColorWeb extends WebPlugin {
  async getSystemColors() {
    // Web implementation - return null as dynamic colors are Android-only
    return { colors: null }
  }

  async isSupported() {
    return { supported: false }
  }
}

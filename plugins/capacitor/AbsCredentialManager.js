import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsCredentialManagerWeb extends WebPlugin {
  async saveCredential() {
    // Web fallback: no-op
    console.log('AbsCredentialManager: saveCredential not supported on web')
  }

  async getCredential() {
    // Web fallback: return empty
    console.log('AbsCredentialManager: getCredential not supported on web')
    return { username: null, password: null }
  }
}

const AbsCredentialManager = registerPlugin('AbsCredentialManager', {
  web: () => new AbsCredentialManagerWeb()
})

export { AbsCredentialManager }

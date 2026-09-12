import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsCertificateWeb extends WebPlugin {
  async getClientCertificateAlias() {
    return { alias: null }
  }

  async selectClientCertificate() {
    throw this.unimplemented('Client certificates are only supported on Android')
  }

  async clearClientCertificate() {
    return {}
  }

  async setActiveCertificateAlias() {
    return {}
  }
}

const AbsCertificate = registerPlugin('AbsCertificate', {
  web: () => new AbsCertificateWeb()
})

export { AbsCertificate }

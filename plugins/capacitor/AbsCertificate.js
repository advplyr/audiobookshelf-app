import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsCertificateWeb extends WebPlugin {
  async pickCertificate() {
    return Promise.reject(new Error('Client certificate selection not supported on this platform'))
  }
}

const AbsCertificate = registerPlugin('AbsCertificate', {
  web: () => new AbsCertificateWeb()
})

export { AbsCertificate }


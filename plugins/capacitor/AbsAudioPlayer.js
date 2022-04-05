import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsAudioPlayerWeb extends WebPlugin {
  constructor() {
    super()
  }
}

const AbsAudioPlayer = registerPlugin('AbsAudioPlayer', {
  web: () => new AbsAudioPlayerWeb()
})

export { AbsAudioPlayer }
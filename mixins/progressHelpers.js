export default {
  computed: {
    localProgressForServerItem() {
      if (!this.serverLibraryItemId) return null
      return this.$store.getters['globals/getLocalMediaProgressByServerItemId'](this.serverLibraryItemId, this.serverEpisodeId)
    }
  },
  methods: {
    getFreshestProgress(server, local) {
      if (!server && !local) return null
      if (!server) return local
      if (!local) return server
      return local.lastUpdate > server.lastUpdate ? local : server
    }
  }
}

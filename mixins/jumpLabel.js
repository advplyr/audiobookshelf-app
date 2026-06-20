export default {
  methods: {
    getJumpLabel(seconds) {
      const val = Number(seconds)
      if (isNaN(val)) return ''
      const useMinutes = val >= 120 // keep 60s as seconds
      const key = useMinutes ? 'UnitMinutesShort' : 'UnitSecondsShort'
      const unitValue = useMinutes ? val / 60 : val
      return this.$getString(key, [this.$formatNumber(unitValue)])
    }
  }
}

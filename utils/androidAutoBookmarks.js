function startupBookmarkConfig(deviceData) {
  const configs = deviceData?.serverConnectionConfigs || []
  const selected = configs.find((config) => config.id === deviceData.lastServerConnectionConfigId)
  return selected || (!deviceData?.lastServerConnectionConfigId && configs.length === 1 ? configs[0] : null)
}

module.exports = { startupBookmarkConfig }

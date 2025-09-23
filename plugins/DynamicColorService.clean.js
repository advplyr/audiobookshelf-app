class DynamicColorService {
  constructor() {
    this.isSupported = false
    this.currentColors = null
    this.DynamicColor = null
  }

  async debugInitialize() {
    console.log('DynamicColorService: Starting DEBUG initialization...')
    try {
      // Use the Capacitor plugin directly since we're in the app
      if (!window.Capacitor || !window.Capacitor.Plugins || !window.Capacitor.Plugins.DynamicColor) {
        console.log('DynamicColorService: Capacitor plugin not available, using defaults')
        return
      }

      const result = await window.Capacitor.Plugins.DynamicColor.getSystemColors()
      console.log('DynamicColorService: Got result from getSystemColors:', JSON.stringify(result, null, 2))

      if (result && result.colors) {
        this.currentColors = result.colors
        console.log('DynamicColorService: Colors received successfully - NOT APPLYING (DEBUG MODE)')
        console.log('Available colors:', Object.keys(result.colors))

        // Log what each color looks like
        Object.entries(result.colors).forEach(([key, value]) => {
          console.log(`  ${key}: ${value}`)
        })

        console.log('DynamicColorService: In normal mode, these colors would be applied to CSS variables')
      } else {
        console.log('DynamicColorService: No colors returned from plugin')
      }
    } catch (error) {
      console.error('Failed to load system colors in DEBUG mode:', error)
    }
  }

  async safeInitialize() {
    console.log('DynamicColorService: Starting SAFE initialization...')

    if (!window.Capacitor || !window.Capacitor.Plugins || !window.Capacitor.Plugins.DynamicColor) {
      console.log('DynamicColorService: Capacitor plugin not available, using defaults')
      return
    }

    try {
      const result = await window.Capacitor.Plugins.DynamicColor.getSystemColors()
      console.log('DynamicColorService: Got result from getSystemColors:', result)

      if (result && result.colors) {
        console.log('DynamicColorService: Colors received, applying SAFELY...')
        this.applySafeColors(result.colors)
      } else {
        console.warn('DynamicColorService: No colors in result')
      }
    } catch (error) {
      console.error('DynamicColorService: Error getting system colors:', error)
    }
  }

  applySafeColors(colors) {
    console.log('DynamicColorService: Applying ONLY essential colors to avoid breaking layout...')

    const root = document.documentElement
    if (!root) {
      console.error('DynamicColorService: Cannot access document root')
      return
    }

    // Only apply the most essential colors that won't break layout
    const safeColors = {
      surface: colors.surface,
      'on-surface': colors.onSurface,
      primary: colors.primary,
      'on-primary': colors.onPrimary
    }

    try {
      Object.entries(safeColors).forEach(([name, hex]) => {
        if (hex) {
          const rgb = this.hexToRgbString(hex)
          const varName = `--md-sys-color-${name}`

          console.log(`DynamicColorService: Setting ${varName} to ${rgb}`)
          root.style.setProperty(varName, rgb)
        }
      })

      console.log('DynamicColorService: SAFE color application completed successfully')
    } catch (error) {
      console.error('DynamicColorService: Error applying safe colors:', error)
    }
  }

  hexToRgbString(hex) {
    const rgb = this.hexToRgb(hex)
    if (rgb) {
      return `${rgb.r} ${rgb.g} ${rgb.b}`
    }
    return '0 0 0'
  }

  hexToRgb(hex) {
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex)
    return result
      ? {
          r: parseInt(result[1], 16),
          g: parseInt(result[2], 16),
          b: parseInt(result[3], 16)
        }
      : null
  }
}

export default DynamicColorService

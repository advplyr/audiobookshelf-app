class DynamicColorService {
  constructor() {
    this.isSupported = false
    this.currentColors = null
    this.DynamicColor = null
    this.isInitialized = false
  }

  // Early initialization that applies cached colors immediately
  applyStoredColors() {
    console.log('DynamicColorService: Applying stored colors for immediate consistency...')

    try {
      const storedColors = localStorage.getItem('dynamicColors')
      if (storedColors) {
        const colors = JSON.parse(storedColors)
        console.log('DynamicColorService: Found stored colors, applying immediately...')
        this.applyColorsToDOM(colors)
        this.currentColors = colors
        return true
      } else {
        console.log('DynamicColorService: No stored colors found')
        return false
      }
    } catch (error) {
      console.error('DynamicColorService: Error applying stored colors:', error)
      return false
    }
  }

  // Store colors in localStorage for next app load
  storeColors(colors) {
    try {
      localStorage.setItem('dynamicColors', JSON.stringify(colors))
      console.log('DynamicColorService: Colors stored in localStorage')
    } catch (error) {
      console.error('DynamicColorService: Error storing colors:', error)
    }
  }

  // Apply colors directly to DOM
  applyColorsToDOM(colors) {
    console.log('=== APPLYING COLORS TO DOM ===')
    console.log('DynamicColorService: Applying colors to DOM...')
    console.log('DynamicColorService: Colors object received:', colors)
    console.log('DynamicColorService: Number of colors to apply:', Object.keys(colors).length)

    const root = document.documentElement
    if (!root) {
      console.error('DynamicColorService: Cannot access document root')
      return
    }

    // Apply all Material You color variables
    const colorMapping = {
      '--md-sys-color-primary': colors.primary,
      '--md-sys-color-on-primary': colors.onPrimary,
      '--md-sys-color-primary-container': colors.primaryContainer,
      '--md-sys-color-on-primary-container': colors.onPrimaryContainer,
      '--md-sys-color-secondary': colors.secondary,
      '--md-sys-color-on-secondary': colors.onSecondary,
      '--md-sys-color-secondary-container': colors.secondaryContainer,
      '--md-sys-color-on-secondary-container': colors.onSecondaryContainer,
      '--md-sys-color-tertiary': colors.tertiary,
      '--md-sys-color-on-tertiary': colors.onTertiary,
      '--md-sys-color-tertiary-container': colors.tertiaryContainer,
      '--md-sys-color-on-tertiary-container': colors.onTertiaryContainer,
      '--md-sys-color-error': colors.error,
      '--md-sys-color-on-error': colors.onError,
      '--md-sys-color-error-container': colors.errorContainer,
      '--md-sys-color-on-error-container': colors.onErrorContainer,
      '--md-sys-color-background': colors.background,
      '--md-sys-color-on-background': colors.onBackground,
      '--md-sys-color-surface': colors.surface,
      '--md-sys-color-on-surface': colors.onSurface,
      '--md-sys-color-surface-variant': colors.surfaceVariant,
      '--md-sys-color-on-surface-variant': colors.onSurfaceVariant,
      '--md-sys-color-outline': colors.outline,
      '--md-sys-color-outline-variant': colors.outlineVariant,
      '--md-sys-color-surface-dim': colors.surfaceDim,
      '--md-sys-color-surface-bright': colors.surfaceBright,
      '--md-sys-color-surface-container-lowest': colors.surfaceContainerLowest,
      '--md-sys-color-surface-container-low': colors.surfaceContainerLow,
      '--md-sys-color-surface-container': colors.surfaceContainer,
      '--md-sys-color-surface-container-high': colors.surfaceContainerHigh,
      '--md-sys-color-surface-container-highest': colors.surfaceContainerHighest,
      '--md-sys-color-inverse-surface': colors.inverseSurface,
      '--md-sys-color-inverse-on-surface': colors.inverseOnSurface,
      '--md-sys-color-inverse-primary': colors.inversePrimary
    }

    // Apply each color variable
    let appliedCount = 0
    Object.entries(colorMapping).forEach(([property, colorHex]) => {
      if (colorHex) {
        const rgb = this.hexToRgbString(colorHex)
        root.style.setProperty(property, rgb)
        appliedCount++
        console.log(`DynamicColorService: Applied ${property} = ${colorHex} (rgb: ${rgb})`)
      } else {
        console.warn(`DynamicColorService: Missing color for ${property}`)
      }
    })

    // Mark that Material You colors are active
    root.setAttribute('data-monet-active', 'true')

    console.log(`DynamicColorService: Successfully applied ${appliedCount} color variables to DOM`)
    console.log('DynamicColorService: Material You active flag set on document')
    console.log('=== END APPLYING COLORS TO DOM ===')
  }

  // Main entry point - calls gentle initialization with theme-specific colors
  async initialize(theme = null) {
    console.log('DynamicColorService: Main initialize() called with theme:', theme)
    return this.gentleInitialize(theme)
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

  async gentleInitialize(theme = null) {
    console.log('=== DYNAMIC COLOR SERVICE DEBUG ===')
    console.log('DynamicColorService: Starting GENTLE initialization with theme:', theme)
    console.log('DynamicColorService: Current document theme:', document.documentElement.dataset.theme)
    console.log('DynamicColorService: Window media query dark:', window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
    console.log('DynamicColorService: Capacitor available:', !!window.Capacitor)
    console.log('DynamicColorService: Capacitor.Plugins available:', !!(window.Capacitor && window.Capacitor.Plugins))
    console.log('DynamicColorService: DynamicColor plugin available:', !!(window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DynamicColor))

    if (!window.Capacitor || !window.Capacitor.Plugins || !window.Capacitor.Plugins.DynamicColor) {
      console.log('DynamicColorService: Capacitor plugin not available, using defaults')
      console.log('DynamicColorService: This is normal in web browser - Material You only works on Android device')
      return
    }

    try {
      // Test if the plugin is working at all
      console.log('DynamicColorService: Testing plugin availability...')
      const supportResult = await window.Capacitor.Plugins.DynamicColor.isSupported()
      console.log('DynamicColorService: Plugin support check result:', supportResult)

      // Determine if we should request dark or light colors
      let isDarkTheme = false
      if (theme === 'dark') {
        isDarkTheme = true
        console.log('DynamicColorService: Theme is explicitly dark')
      } else if (theme === 'light') {
        isDarkTheme = false
        console.log('DynamicColorService: Theme is explicitly light')
      } else if (theme === 'system') {
        // Use system preference for system theme
        isDarkTheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
        console.log('DynamicColorService: System theme - detected dark:', isDarkTheme)
      } else {
        // Default to current document theme
        isDarkTheme = document.documentElement.dataset.theme === 'dark'
        console.log('DynamicColorService: Using document theme - dark:', isDarkTheme)
      }

      console.log('DynamicColorService: Final isDarkTheme decision:', isDarkTheme)
      console.log('DynamicColorService: About to call getSystemColors with isDark:', isDarkTheme)

      // Request theme-specific colors
      const result = await window.Capacitor.Plugins.DynamicColor.getSystemColors({ isDark: isDarkTheme })
      console.log('DynamicColorService: Got result from getSystemColors:', result)

      if (result && result.colors) {
        console.log('DynamicColorService: SUCCESS - Colors received!')
        console.log('DynamicColorService: Sample colors:')
        console.log('  - primary:', result.colors.primary)
        console.log('  - surface:', result.colors.surface)
        console.log('  - background:', result.colors.background)
        console.log('  - onSurface:', result.colors.onSurface)
        console.log('DynamicColorService: Total colors received:', Object.keys(result.colors).length)

        this.currentColors = result.colors
        this.storeColors(result.colors) // Store for next app load
        this.applyColorsToDOM(result.colors) // Apply the full set of colors
        this.isInitialized = true
        console.log('DynamicColorService: Colors applied to DOM successfully')
      } else {
        console.warn('DynamicColorService: ERROR - No colors in result')
        console.warn('DynamicColorService: Full result object:', result)
      }
      console.log('=== END DYNAMIC COLOR SERVICE DEBUG ===')
    } catch (error) {
      console.error('DynamicColorService: ERROR - Exception during getSystemColors:', error)
      console.error('DynamicColorService: Error details:', error.message)
      console.log('=== END DYNAMIC COLOR SERVICE DEBUG (WITH ERROR) ===')
    }
  }

  applyGentleColors(colors) {
    console.log('DynamicColorService: Applying colors with SAFETY VALIDATION...')

    try {
      // First, let's see what colors we're working with
      console.log('DynamicColorService: Analyzing colors before applying...')
      console.log('Primary color:', colors.primary)
      console.log('Surface color:', colors.surface)
      console.log('Background color:', colors.background)
      console.log('OnSurface color:', colors.onSurface)
      console.log('SurfaceVariant color:', colors.surfaceVariant)
      console.log('SurfaceDim color:', colors.surfaceDim)
      console.log('SurfaceContainer color:', colors.surfaceContainer)

      // SAFETY CHECK: Validate that we have valid colors before applying anything
      if (!colors.surface || !colors.onSurface) {
        console.error('DynamicColorService: CRITICAL - Missing essential colors, aborting to prevent blank screen')
        return
      }

      // Use background color if available, otherwise surface
      const mainSurfaceColor = colors.background || colors.surface
      const surfaceContainerColor = colors.surface
      const surfaceVariantColor = colors.surfaceVariant || colors.surface
      const onSurfaceColor = colors.onSurface

      // SAFETY CHECK: Validate hex color format
      const isValidHex = (hex) => /^#?([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(hex)

      if (!isValidHex(mainSurfaceColor) || !isValidHex(onSurfaceColor)) {
        console.error('DynamicColorService: CRITICAL - Invalid hex color format, aborting to prevent CSS errors')
        console.error('Main surface:', mainSurfaceColor, 'On surface:', onSurfaceColor)
        return
      }

      // Convert to RGB and validate
      const mainSurfaceRgb = this.hexToRgbString(mainSurfaceColor)
      const surfaceContainerRgb = this.hexToRgbString(surfaceContainerColor)
      const surfaceVariantRgb = this.hexToRgbString(surfaceVariantColor)
      const onSurfaceRgb = this.hexToRgbString(onSurfaceColor)

      // SAFETY CHECK: Ensure RGB conversion succeeded
      if (mainSurfaceRgb === '0 0 0' && mainSurfaceColor !== '#000000') {
        console.error('DynamicColorService: CRITICAL - RGB conversion failed, aborting')
        return
      }

      console.log('VALIDATED COLOR MAPPING:')
      console.log('Main Surface (background elements) RGB:', mainSurfaceRgb)
      console.log('Surface Container (cards, player) RGB:', surfaceContainerRgb)
      console.log('Surface Variant RGB:', surfaceVariantRgb)
      console.log('OnSurface RGB:', onSurfaceRgb)

      // Remove any existing style to prevent conflicts
      const existingStyle = document.getElementById('dynamic-colors-style')
      if (existingStyle) {
        console.log('DynamicColorService: Removing existing dynamic color styles')
        existingStyle.remove()
      }

      // SAFETY: Apply colors using CSS variables only (no direct style manipulation)
      const root = document.documentElement
      if (!root) {
        console.error('DynamicColorService: Cannot access document root')
        return
      }

      console.log('DynamicColorService: Applying Material You colors (EXCLUDING main surface to avoid white screen)...')

      // Apply all colors EXCEPT the main surface color which causes white screen
      console.log('DynamicColorService: Skipping --md-sys-color-surface to prevent invisible overlay issue')

      // Main background and surface colors (EXCLUDING main surface)
      root.style.setProperty('--md-sys-color-on-surface', onSurfaceRgb)
      root.style.setProperty('--md-sys-color-surface-variant', surfaceVariantRgb)
      root.style.setProperty('--md-sys-color-surface-container', surfaceContainerRgb)

      // Apply surface-dim for bookshelf rows
      const surfaceDimRgb = this.hexToRgbString(colors.surfaceDim || mainSurfaceColor)
      root.style.setProperty('--md-sys-color-surface-dim', surfaceDimRgb)
      console.log('Applied surface-dim for bookshelf rows:', surfaceDimRgb)

      // Apply additional surface variants for navigation and taskbar (darker than main)
      if (colors.surfaceContainerHigh) {
        const surfaceContainerHighRgb = this.hexToRgbString(colors.surfaceContainerHigh)
        root.style.setProperty('--md-sys-color-surface-container-high', surfaceContainerHighRgb)
        console.log('Applied surface-container-high for navigation:', surfaceContainerHighRgb)
      }

      if (colors.surfaceContainerLow) {
        const surfaceContainerLowRgb = this.hexToRgbString(colors.surfaceContainerLow)
        root.style.setProperty('--md-sys-color-surface-container-low', surfaceContainerLowRgb)
        console.log('Applied surface-container-low:', surfaceContainerLowRgb)
      }

      // Apply primary colors for accents
      if (colors.primary) {
        const primaryRgb = this.hexToRgbString(colors.primary)
        root.style.setProperty('--md-sys-color-primary', primaryRgb)
        console.log('Applied primary color:', primaryRgb)
      }

      if (colors.onPrimary) {
        const onPrimaryRgb = this.hexToRgbString(colors.onPrimary)
        root.style.setProperty('--md-sys-color-on-primary', onPrimaryRgb)
        console.log('Applied on-primary color:', onPrimaryRgb)
      }

      // Apply secondary colors
      if (colors.secondary) {
        const secondaryRgb = this.hexToRgbString(colors.secondary)
        root.style.setProperty('--md-sys-color-secondary', secondaryRgb)
        console.log('Applied secondary color:', secondaryRgb)
      }

      // Apply tertiary colors
      if (colors.tertiary) {
        const tertiaryRgb = this.hexToRgbString(colors.tertiary)
        root.style.setProperty('--md-sys-color-tertiary', tertiaryRgb)
        console.log('Applied tertiary color:', tertiaryRgb)
      }

      console.log('DynamicColorService: ULTRA-SAFE SOLUTION - Only apply colors to specific .bg-surface-dynamic class')

      // DO NOT apply ANY surface colors to global CSS variables
      console.log('DynamicColorService: Skipping ALL global CSS surface variables to keep layouts completely safe')

      // Only apply colors through the specific .bg-surface-dynamic class
      const dynamicSurfaceStyleId = 'dynamic-surface-style'
      const existingDynamicStyle = document.getElementById(dynamicSurfaceStyleId)
      if (existingDynamicStyle) {
        existingDynamicStyle.remove()
      }

      const dynamicStyleElement = document.createElement('style')
      dynamicStyleElement.id = dynamicSurfaceStyleId
      dynamicStyleElement.textContent = `
        /* ONLY APPLY MATERIAL YOU COLORS TO SPECIFIC SAFE CLASS */
        .bg-surface-dynamic {
          background-color: rgb(${mainSurfaceRgb}) !important;
        }
      `
      document.head.appendChild(dynamicStyleElement)
      console.log('Applied Material You surface color ONLY to .bg-surface-dynamic class:', mainSurfaceRgb)

      // Apply safe non-surface colors for fuller Material You theming
      root.style.setProperty('--md-sys-color-on-surface', onSurfaceRgb)
      console.log('Applied on-surface color:', onSurfaceRgb)

      // Apply primary colors for accents and buttons
      if (colors.primary) {
        const primaryRgb = this.hexToRgbString(colors.primary)
        root.style.setProperty('--md-sys-color-primary', primaryRgb)
        console.log('Applied primary color:', primaryRgb)
      }

      if (colors.onPrimary) {
        const onPrimaryRgb = this.hexToRgbString(colors.onPrimary)
        root.style.setProperty('--md-sys-color-on-primary', onPrimaryRgb)
        console.log('Applied on-primary color:', onPrimaryRgb)
      }

      // Apply secondary colors for variety
      if (colors.secondary) {
        const secondaryRgb = this.hexToRgbString(colors.secondary)
        root.style.setProperty('--md-sys-color-secondary', secondaryRgb)
        console.log('Applied secondary color:', secondaryRgb)
      }

      // Apply surface variants for different elements
      if (colors.surfaceVariant) {
        const surfaceVariantRgb = this.hexToRgbString(colors.surfaceVariant)
        root.style.setProperty('--md-sys-color-surface-variant', surfaceVariantRgb)
        console.log('Applied surface-variant color:', surfaceVariantRgb)
      }

      if (colors.surfaceContainer) {
        const surfaceContainerRgb = this.hexToRgbString(colors.surfaceContainer)
        root.style.setProperty('--md-sys-color-surface-container', surfaceContainerRgb)
        console.log('Applied surface-container color:', surfaceContainerRgb)
      }

      // Apply primary colors for accents
      if (colors.primary) {
        const primaryRgb = this.hexToRgbString(colors.primary)
        root.style.setProperty('--md-sys-color-primary', primaryRgb)
        console.log('Applied primary color:', primaryRgb)
      }

      if (colors.onPrimary) {
        const onPrimaryRgb = this.hexToRgbString(colors.onPrimary)
        root.style.setProperty('--md-sys-color-on-primary', onPrimaryRgb)
        console.log('Applied on-primary color:', onPrimaryRgb)
      }

      console.log('DynamicColorService: Material You colors applied successfully with safe global layout elements')
    } catch (error) {
      console.error('DynamicColorService: CRITICAL ERROR in color application:', error)
      console.error('DynamicColorService: Removing any partial styles to prevent blank screen')

      // Emergency cleanup: remove any dynamic styles that might have been partially applied
      const existingStyle = document.getElementById('dynamic-colors-style')
      if (existingStyle) {
        existingStyle.remove()
      }
    }
  }

  // Manual refresh of colors - useful for settings toggle
  async refreshColors() {
    console.log('DynamicColorService: Manual refresh requested...')

    if (this.isInitialized) {
      console.log('DynamicColorService: Service already initialized, doing quick refresh...')
      return this.gentleInitialize()
    } else {
      console.log('DynamicColorService: Service not initialized, doing full initialization...')
      return this.initialize()
    }
  }

  // Clear stored colors from localStorage
  clearStoredColors() {
    try {
      localStorage.removeItem('dynamicColors')
      console.log('DynamicColorService: Stored colors cleared')
    } catch (error) {
      console.error('DynamicColorService: Error clearing stored colors:', error)
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

  // Clear dynamic colors and restore defaults
  clearDynamicColors() {
    console.log('DynamicColorService: Clearing dynamic colors...')
    const root = document.documentElement
    if (root) {
      // Remove the Material You active flag
      root.removeAttribute('data-monet-active')

      // Clear stored colors
      try {
        localStorage.removeItem('dynamicColors')
      } catch (error) {
        console.error('DynamicColorService: Error clearing stored colors:', error)
      }
    }
    console.log('DynamicColorService: Dynamic colors cleared')
  }
}

export default DynamicColorService

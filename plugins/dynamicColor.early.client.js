import DynamicColorService from '~/plugins/DynamicColorService.js'

export default ({ app }, inject) => {
  console.log('DynamicColor EARLY plugin: Initializing for immediate color consistency...')
  const dynamicColorService = new DynamicColorService()

  // Apply stored colors immediately before rendering
  if (process.client) {
    console.log('DynamicColor EARLY plugin: Applying stored colors immediately...')

    // Try to apply stored colors synchronously
    try {
      const hasStoredColors = dynamicColorService.applyStoredColors()
      if (hasStoredColors) {
        console.log('DynamicColor EARLY plugin: Successfully applied stored colors for consistent theming')
      } else {
        console.log('DynamicColor EARLY plugin: No stored colors available, will use defaults until initialization')
      }
    } catch (error) {
      console.error('DynamicColor EARLY plugin: Error applying stored colors:', error)
    }
  }

  // Inject the service into the Vue context
  inject('dynamicColor', dynamicColorService)
  console.log('DynamicColor EARLY plugin: Service injected as $dynamicColor')

  // Initialize when the app is ready to get fresh colors
  if (process.client) {
    console.log('DynamicColor EARLY plugin: Scheduling fresh color initialization...')
    app.router.onReady(() => {
      console.log('DynamicColor EARLY plugin: Router ready, getting fresh colors...')
      setTimeout(() => {
        console.log('DynamicColor EARLY plugin: Fetching fresh dynamic colors...')
        try {
          if (document.body && document.documentElement) {
            console.log('DynamicColor EARLY plugin: DOM ready, calling gentle initialize...')

            // Get current theme from localStorage or default to 'system'
            let currentTheme = 'system'
            try {
              const storedTheme = localStorage.getItem('theme')
              if (storedTheme && ['system', 'dark', 'light'].includes(storedTheme)) {
                currentTheme = storedTheme
              }
            } catch (e) {
              console.log('DynamicColor EARLY plugin: Could not read theme from localStorage, using system default')
            }

            console.log('DynamicColor EARLY plugin: Initializing with theme:', currentTheme)
            dynamicColorService.gentleInitialize(currentTheme)
          } else {
            console.warn('DynamicColor EARLY plugin: DOM not ready, skipping fresh initialization')
          }
        } catch (error) {
          console.error('DynamicColor EARLY plugin: Fresh initialization failed:', error)
          console.log('App will continue with cached/default theme')
        }
      }, 1000) // Shorter delay since we already have colors applied
    })
  }
}

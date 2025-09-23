<template>
  <div class="splash-screen">
    <div class="splash-content">
      <ui-tomesonic-splash-icon :size="logoSize" class="splash-logo" />
      <h1 class="splash-title">TomeSonic</h1>
      <div v-if="showLoading" class="loading-indicator">
        <div class="loading-dots">
          <span class="dot dot-1"></span>
          <span class="dot dot-2"></span>
          <span class="dot dot-3"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'SplashScreen',
  props: {
    showLoading: {
      type: Boolean,
      default: true
    }
  },
  computed: {
    logoSize() {
      // Responsive logo size
      if (process.client) {
        return window.innerWidth < 768 ? 120 : 160
      }
      return 160
    },
    logoColor() {
      // Use Material You primary color
      return 'primary'
    }
  }
}
</script>

<style scoped>
.splash-screen {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgb(var(--md-sys-color-surface));
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.splash-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  animation: fadeInUp 800ms cubic-bezier(0.2, 0, 0, 1) forwards;
  opacity: 0;
  transform: translateY(20px);
}

.splash-logo {
  margin-bottom: 24px;
  animation: logoFloat 2s ease-in-out infinite;
}

.splash-title {
  font-size: 2rem;
  font-weight: 500;
  color: rgb(var(--md-sys-color-on-surface));
  margin-bottom: 32px;
  letter-spacing: 0.02em;
}

.loading-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
}

.loading-dots {
  display: flex;
  gap: 8px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgb(var(--md-sys-color-primary));
  animation: dotPulse 1.4s ease-in-out infinite both;
}

.dot-1 {
  animation-delay: -0.32s;
}

.dot-2 {
  animation-delay: -0.16s;
}

.dot-3 {
  animation-delay: 0s;
}

@keyframes fadeInUp {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes logoFloat {
  0%,
  100% {
    transform: translateY(0px);
  }
  50% {
    transform: translateY(-10px);
  }
}

@keyframes dotPulse {
  0%,
  80%,
  100% {
    transform: scale(0.8);
    opacity: 0.5;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

/* Dark mode support */
@media (prefers-color-scheme: dark) {
  .splash-screen {
    background: rgb(var(--md-sys-color-surface));
  }
}

/* Mobile responsiveness */
@media (max-width: 768px) {
  .splash-title {
    font-size: 1.5rem;
  }
}
</style>

class WrappingMarquee {
  #scrollDelay = 2000
  #scrollSpeed = 30

  /**
   * @param {HTMLElement} el 
   */
  constructor(el) {
    this.el = el
    /** @type {HTMLElement} */
    this.pEl = el?.firstElementChild

    this.innerText = ''
    this.isScrolling = false

    /** @type {NodeJS.Timeout} */
    this.timer = null
    /** @type {number} */
    this.animationId = null
  }

  /**
   * Transparent gradient mask shown when text is scrolling left and overflowing right
   * 
   * @param {boolean} showLeft 
   */
  setMask(showLeft) {
    if (!this.el) return
    this.el.style.maskImage = showLeft ? 'linear-gradient(90deg, transparent 0%, #fff 10%, #000 90%, transparent)' : 'linear-gradient(90deg, #000 90%, transparent)'
  }

  startScroll() {
    if (this.isScrolling) {
      console.warn('Already scrolling')
      return
    }

    this.isScrolling = true
    this.setMask(true)

    let textScrollAmount = this.el.scrollWidth
    this.pEl.innerHTML = this.innerText + '&nbsp;'.repeat(15)
    let totalScrollAmount = this.el.scrollWidth
    let scrollDuration = totalScrollAmount * this.#scrollSpeed

    this.pEl.innerHTML = this.pEl.innerHTML + this.innerText

    let done = false
    let start, previousTimeStamp

    const step = (timeStamp) => {
      if (start === undefined) {
        start = timeStamp
      }
      const elapsed = timeStamp - start

      if (this.isScrolling && previousTimeStamp !== timeStamp) {
        const amountToMove = Math.min(elapsed / scrollDuration * totalScrollAmount, totalScrollAmount)
        this.pEl.style.transform = `translateX(-${amountToMove}px)`
        if (amountToMove === totalScrollAmount) done = true
        if (amountToMove > textScrollAmount) this.setMask(false)
      }

      if (!this.isScrolling || done) { // canceled or done
        this.isScrolling = false
        this.pEl.style.transform = 'translateX(0px)'
        this.pEl.innerText = this.innerText
        this.setMask(false)
        if (done) {
          this.startTimer()
        }
      } else if (elapsed < scrollDuration) { // step
        previousTimeStamp = timeStamp
        this.animationId = window.requestAnimationFrame(step)
      }
    }
    this.animationId = window.requestAnimationFrame(step)
  }

  startTimer() {
    clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.startScroll()
    }, this.#scrollDelay)
  }

  reset() {
    clearTimeout(this.timer)
    this.timer = null
    this.isScrolling = false
    window.cancelAnimationFrame(this.animationId)
  }

  /**
   * Initialize and start marquee if text overflows container
   * resets the marquee if already active
   * 
   * @param {string} innerText 
   */
  init(innerText) {
    if (!this.el || !this.pEl) return

    this.reset()

    this.innerText = innerText
    this.pEl.innerText = innerText
    this.pEl.style.transform = 'translateX(0px)'

    if (this.el.scrollWidth > this.el.clientWidth) {
      this.setMask(false)
      this.startTimer()
    } else {
      this.el.style.maskImage = ''
    }
  }
}
export default WrappingMarquee
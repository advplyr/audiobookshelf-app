import Vue from "vue"
import Toast from "vue-toastification"
import "vue-toastification/dist/index.css"

const options = {
  hideProgressBar: true,
  position: 'bottom-center'
}

Vue.use(Toast, options)
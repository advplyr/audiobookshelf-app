import Vue from 'vue'
import { Haptics, ImpactStyle, NotificationType } from '@capacitor/haptics'

const hapticsImpactHeavy = async () => {
  await Haptics.impact({ style: ImpactStyle.Heavy });
}
Vue.prototype.$hapticsImpactHeavy = hapticsImpactHeavy

const hapticsImpactMedium = async () => {
  await Haptics.impact({ style: ImpactStyle.Medium });
}
Vue.prototype.$hapticsImpactMedium = hapticsImpactMedium

const hapticsImpactLight = async () => {
  await Haptics.impact({ style: ImpactStyle.Light });
};
Vue.prototype.$hapticsImpactLight = hapticsImpactLight

const hapticsVibrate = async () => {
  await Haptics.vibrate();
};
Vue.prototype.$hapticsVibrate = hapticsVibrate

const hapticsNotificationSuccess = async () => {
  await Haptics.notification({ type: NotificationType.Success });
};
Vue.prototype.$hapticsNotificationSuccess = hapticsNotificationSuccess

const hapticsNotificationWarning = async () => {
  await Haptics.notification({ type: NotificationType.Warning });
};
Vue.prototype.$hapticsNotificationWarning = hapticsNotificationWarning

const hapticsNotificationError = async () => {
  await Haptics.notification({ type: NotificationType.Error });
};
Vue.prototype.$hapticsNotificationError = hapticsNotificationError

const hapticsSelectionStart = async () => {
  await Haptics.selectionStart();
};
Vue.prototype.$hapticsSelectionStart = hapticsSelectionStart

const hapticsSelectionChanged = async () => {
  await Haptics.selectionChanged();
};
Vue.prototype.$hapticsSelectionChanged = hapticsSelectionChanged

const hapticsSelectionEnd = async () => {
  await Haptics.selectionEnd();
};
Vue.prototype.$hapticsSelectionEnd = hapticsSelectionEnd
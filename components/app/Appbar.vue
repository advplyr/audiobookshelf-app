<template>
  <div class="w-full h-16 bg-primary relative">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full z-30 flex items-center px-2">
      <nuxt-link v-show="!showBack" to="/" class="mr-3">
        <img src="/Logo.png" class="h-10 w-10" />
      </nuxt-link>
      <a v-if="showBack" @click="back" class="rounded-full h-10 w-10 flex items-center justify-center hover:bg-white hover:bg-opacity-10 mr-2 cursor-pointer">
        <span class="material-icons text-3xl text-white">arrow_back</span>
      </a>
      <div>
        <p class="text-lg font-book leading-4">AudioBookshelf</p>
        <!-- <a class="text-xs text-success leading-4 flex items-center py-1" :href="appListingUrl" target="_blank">Update available! <span class="material-icons text-sm leading-4 px-2">launch</span></a> -->
      </div>
      <div class="flex-grow" />
      <ui-menu :label="username" :items="menuItems" @action="menuAction" class="ml-5" />
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      menuItems: [
        {
          value: 'account',
          text: 'Account',
          to: '/account'
        },
        {
          value: 'logout',
          text: 'Logout'
        }
      ]
    }
  },
  computed: {
    showBack() {
      return this.$route.name !== 'index'
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user ? this.user.username : 'err'
    },
    // hasUpdate() {
    //   return this.$store.state.hasUpdate
    // },
    appListingUrl() {
      if (this.$platform === 'android') {
        return process.env.ANDROID_APP_URL
      } else {
        return process.env.IOS_APP_URL
      }
    }
  },
  methods: {
    back() {
      if (this.$route.name === 'audiobook-id-edit') {
        this.$router.push(`/audiobook/${this.$route.params.id}`)
      } else {
        this.$router.push('/')
      }
    },
    logout() {
      this.$axios.$post('/logout').catch((error) => {
        console.error(error)
      })
      this.$server.logout()
      this.$router.push('/connect')
    },
    menuAction(action) {
      if (action === 'logout') {
        this.logout()
      }
    }
  },
  mounted() {}
}
</script>

<style>
#appbar {
  box-shadow: 0px 5px 5px #11111155;
}
</style>
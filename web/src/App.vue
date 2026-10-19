<template>
  <div id="app">
    <LoginView v-if="!authenticated" @authenticated="onAuthenticated" />
    <template v-else>
      <header class="app-header">
        <div class="brand">
          <span class="brand-mark">EA</span>
          <div>
            <h1>ErrorAgent</h1>
            <p>AI 异常巡检</p>
          </div>
        </div>
        <div class="account">
          <span>{{ currentUser }}</span>
          <el-button type="text" @click="logout">退出</el-button>
        </div>
      </header>
      <main class="app-main">
        <InspectDashboard />
      </main>
    </template>
  </div>
</template>

<script>
import InspectDashboard from './components/InspectDashboard.vue';
import LoginView from './components/LoginView.vue';
import { authorization, clearCredentials, username } from './auth';

export default {
  name: 'App',
  components: { InspectDashboard, LoginView },
  data() {
    return { authenticated: !!authorization(), currentUser: username() };
  },
  mounted() {
    window.addEventListener('erroragent:unauthorized', this.onUnauthorized);
  },
  beforeDestroy() {
    window.removeEventListener('erroragent:unauthorized', this.onUnauthorized);
  },
  methods: {
    onAuthenticated(user) {
      this.currentUser = user;
      this.authenticated = true;
    },
    onUnauthorized() {
      this.authenticated = false;
      this.currentUser = '';
      this.$message.warning('登录已失效，请重新登录');
    },
    logout() {
      clearCredentials();
      this.authenticated = false;
      this.currentUser = '';
    }
  }
};
</script>

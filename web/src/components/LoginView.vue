<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">EA</div>
      <h1>ErrorAgent</h1>
      <p class="login-subtitle">AI 异常巡检控制台</p>
      <el-form ref="form" :model="form" @submit.native.prevent="login">
        <el-form-item>
          <el-input v-model.trim="form.username" autocomplete="username" placeholder="管理员账号"
                    prefix-icon="el-icon-user" @keyup.enter.native="login" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" autocomplete="current-password"
                    show-password placeholder="密码" prefix-icon="el-icon-lock"
                    @keyup.enter.native="login" />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-button class="login-button" type="primary" :loading="loading" @click="login">
          登录
        </el-button>
      </el-form>
      <p class="login-hint">账号由 erroragent.admin.* 配置</p>
    </div>
  </div>
</template>

<script>
import api from '../api/client';
import { clearCredentials, saveCredentials } from '../auth';

export default {
  name: 'LoginView',
  data() {
    return {
      form: { username: 'admin', password: '' },
      loading: false,
      error: ''
    };
  },
  methods: {
    async login() {
      if (!this.form.username || !this.form.password) {
        this.error = '请输入账号和密码';
        return;
      }
      this.loading = true;
      this.error = '';
      saveCredentials(this.form.username, this.form.password);
      try {
        await api.get('/inspect/collect/switch');
        this.$emit('authenticated', this.form.username);
      } catch (e) {
        clearCredentials();
        this.error = e.response && e.response.status === 401
          ? '账号或密码错误'
          : '服务暂时不可用，请稍后重试';
      } finally {
        this.loading = false;
      }
    }
  }
};
</script>

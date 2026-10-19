package io.github.liufor02144.erroragent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.UUID;

/**
 * 单个管理员账号 + HTTP Basic。未配置密码时生成随机密码并打印到启动日志。
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 登录页和静态资源必须公开，否则浏览器还没显示登录框就会被 HTTP Basic 拦住。
        // 业务接口统一位于 /inspect/**，仍要求管理员身份。
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${erroragent.admin.username:admin}") String username,
            @Value("${erroragent.admin.password:}") String password) {
        String effective = password;
        if (effective == null || effective.isBlank()) {
            effective = UUID.randomUUID().toString().replace("-", "");
            log.warn("未配置 erroragent.admin.password，本次启动生成的临时密码：{}（用户名 {}）", effective, username);
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(effective))
                .roles("ADMIN")
                .build());
    }
}

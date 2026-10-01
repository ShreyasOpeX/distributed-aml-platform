package com.tradesentry.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/transactions").hasRole("AML_OPERATOR")
                .requestMatchers(HttpMethod.GET,"/api/transactions/**").hasAnyRole("AML_ANALYST","AML_OPERATOR")
                .anyRequest().authenticated())
            .httpBasic(basic -> {});
        return http.build();
    }

    @Bean
    UserDetailsService users(
            @Value("${TRADESENTRY_API_USER}") String username,
            @Value("${TRADESENTRY_API_PASSWORD}") String password,
            PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername(username).password(encoder.encode(password))
                .roles("AML_OPERATOR","AML_ANALYST").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

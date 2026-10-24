package se.geobook.tracker.access.controller;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.service.SessionService;

/**
 * Spring Security owns the filter chain (ADR 0004). Everything is denied unless opened here, so
 * endpoints added later need a session too. Public mode opens every path.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, SessionService sessions) {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    if (sessions.mode() == AuthMode.PUBLIC) {
      http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
      return http.build();
    }
    http.addFilterBefore(new CredentialFilter(sessions), AnonymousAuthenticationFilter.class)
        .exceptionHandling(handling -> handling.authenticationEntryPoint(new SignInEntryPoint()))
        .authorizeHttpRequests(
            requests ->
                requests
                    // Forwards and error pages run after the original request was authorized.
                    .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET, "/login", "/login.html", "/assets/**", "/api/status")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/session")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    return http.build();
  }
}

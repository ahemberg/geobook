package se.geobook.tracker.access.controller;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import se.geobook.tracker.access.domain.AuthMode;
import se.geobook.tracker.access.service.BruteForceGuard;
import se.geobook.tracker.access.service.SessionService;

/**
 * Spring Security owns the filter chain (ADR 0004). Everything is denied unless opened here, so
 * endpoints added later need a session too. Public mode opens every path except the account and app
 * passwords, which answer 404 there.
 */
@Configuration(proxyBeanMethods = false)
// Subcommands run without a web server and need no filter chain.
@ConditionalOnWebApplication
class SecurityConfiguration {
  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      SessionService sessions,
      BruteForceGuard guard,
      ClientConnection connection) {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Spring's CSRF tokens would need the frontend to echo a token; the browser's own
        // Sec-Fetch-Site and Origin headers carry the same answer.
        .addFilterBefore(new PlainHttpWarning(connection), AnonymousAuthenticationFilter.class)
        .addFilterBefore(new CrossOriginFilter(), AnonymousAuthenticationFilter.class);
    if (sessions.mode() == AuthMode.PUBLIC) {
      // Without a login there is no account, and an app password would be a credential nobody
      // needed to mint.
      final AuthenticationEntryPoint notFound =
          (request, response, exception) ->
              ErrorResponses.write(response, HttpServletResponse.SC_NOT_FOUND, "Not found.");
      http.exceptionHandling(handling -> handling.authenticationEntryPoint(notFound))
          .authorizeHttpRequests(
              requests ->
                  requests
                      .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)
                      .permitAll()
                      .requestMatchers(
                          "/account", "/account.html", "/api/account/**", "/api/app-passwords/**")
                      .denyAll()
                      .anyRequest()
                      .permitAll());
      return http.build();
    }
    final ScopeAuthorization scopes = new ScopeAuthorization();
    http.addFilterBefore(
            new CredentialFilter(sessions, guard, connection), AnonymousAuthenticationFilter.class)
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(new SignInEntryPoint(connection))
                    .accessDeniedHandler(scopes))
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
                    .access(scopes));
    return http.build();
  }
}

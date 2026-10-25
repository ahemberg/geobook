package se.geobook.tracker.configuration;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Tests register their own Clock before the context refreshes to move time without sleeping.
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }
}

package com.anitec.backend.identity.infrastructure;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
@Configuration
public class SessionClockConfiguration {
 @Bean public Clock sessionClock() { return Clock.systemUTC(); }
}

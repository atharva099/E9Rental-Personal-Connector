package com.e9rental.config;
import java.time.*;
import org.springframework.context.annotation.*;
@Configuration
public class PracticeConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
}

package com.empresa.pedidos;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj de la aplicación. Se inyecta en lugar de llamar a {@code Instant.now()} para poder fijarlo en tests. */
@Configuration
public class ConfiguracionReloj {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

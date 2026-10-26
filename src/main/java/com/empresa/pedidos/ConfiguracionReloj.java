package com.empresa.pedidos;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj de la aplicación. Se inyecta en lugar de llamar a {@code Instant.now()} para poder fijarlo en tests. */
@Configuration
public class ConfiguracionReloj {

    /**
     * Limita la precisión de los instantes a microsegundos, la de las columnas {@code TIMESTAMP}: con
     * nanosegundos, la base de datos redondearía y la fecha devuelta al crear el pedido no coincidiría
     * con la guardada.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}

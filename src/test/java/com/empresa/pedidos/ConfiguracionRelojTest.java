package com.empresa.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** El reloj de la aplicación da instantes con precisión de microsegundos, la de las columnas TIMESTAMP. */
class ConfiguracionRelojTest {

    @Test
    void limitaLaPrecisionAMicrosegundos() {
        Clock clock = new ConfiguracionReloj().clock();

        for (int i = 0; i < 1_000; i++) {
            Instant instante = Instant.now(clock);
            assertThat(instante.getNano() % 1_000).as("nanosegundos por debajo del microsegundo").isZero();
        }
    }
}

package com.empresa.pedidos.pedido.infrastructure.proveedor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/** Arranque de la configuración del proveedor con valores válidos e inválidos (D6, D8). Solo carga esta configuración. */
class ConfiguracionProveedorTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ConfiguracionProveedor.class)
            .withPropertyValues(
                    "proveedor.url-base=http://localhost:8089",
                    "proveedor.token=secreto-de-prueba",
                    "proveedor.timeout-conexion=1s",
                    "proveedor.timeout-lectura=6s",
                    "proveedor.reintentos.max-intentos=2",
                    "proveedor.reintentos.espera-inicial=200ms",
                    "proveedor.reintentos.multiplicador=2.0",
                    "proveedor.reintentos.jitter=0.5");

    @Test
    void noArrancaSinToken() {
        contexto.withPropertyValues("proveedor.token=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("PROVEEDOR_TOKEN"));
    }

    @Test
    void noArrancaConUnTokenConSaltoDeLineaYNoLoMuestraEnElError() {
        // withPropertyValues recorta los espacios y el salto de línea; una variable de entorno no. Se añade
        // el valor tal cual, con prioridad sobre el resto.
        contexto.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("token-con-salto", Map.of("proveedor.token", "secreto-de-prueba\n"))))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // Toda la cadena de causas, con sus mensajes y trazas: el token no aparece en ninguna.
                    StringWriter traza = new StringWriter();
                    ctx.getStartupFailure().printStackTrace(new PrintWriter(traza));
                    assertThat(traza.toString())
                            .contains("PROVEEDOR_TOKEN contiene caracteres no válidos")
                            .doesNotContain("secreto-de-prueba");
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "proveedor.timeout-lectura=1s",                       // lectura = conexión
            "proveedor.timeout-lectura=500ms",                    // lectura < conexión
            "proveedor.timeout-conexion=0s",
            "proveedor.reintentos.jitter=1.0",                    // Resilience4j exige < 1
            "proveedor.reintentos.espera-inicial=0ms",
            "proveedor.url-base=http://usuario:clave@localhost:8089",
            "proveedor.url-base=ftp://localhost:8089"})
    void noArrancaConUnaConfiguracionInvalida(String propiedad) {
        contexto.withPropertyValues(propiedad)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void arrancaConTokenYNoLoExponeEnElToString() {
        contexto.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ProveedorTarifasHttp.class);
            assertThat(ctx.getBean(ProveedorTarifasProperties.class).toString())
                    .doesNotContain("secreto-de-prueba")
                    .contains("token=***");
        });
    }
}

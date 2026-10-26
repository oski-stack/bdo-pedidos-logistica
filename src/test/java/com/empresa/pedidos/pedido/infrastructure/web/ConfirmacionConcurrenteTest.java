package com.empresa.pedidos.pedido.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.empresa.pedidos.pedido.application.port.out.ProveedorTarifas;
import com.empresa.pedidos.pedido.domain.SolicitudTarifa;
import com.empresa.pedidos.pedido.domain.TarifaEnvio;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirmaciones simultáneas del mismo pedido: una sola llamada al proveedor y datos consistentes
 * (requisito técnico 2, D4, D10).
 *
 * <p>Condiciones de D10:
 * <ul>
 *   <li>Base de datos real (H2) y repositorio real. Solo se sustituye el puerto {@link ProveedorTarifas}
 *       por un falso que retiene la llamada con un {@link CountDownLatch}.</li>
 *   <li><b>Sin {@code @Transactional} en el test</b>: cada petición usa sus propias transacciones, como en
 *       producción. Con una transacción envolvente, la reserva no se confirmaría antes de llamar al
 *       proveedor y el test no probaría nada.</li>
 *   <li>Todas las esperas tienen límite, para que un fallo no bloquee la suite. Sin {@code sleep}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmacionConcurrenteTest {

    private static final long ESPERA_MAXIMA_SEGUNDOS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProveedorRetenido proveedor;

    private ExecutorService hilos;

    @BeforeEach
    void preparar() {
        proveedor.reiniciar();
        hilos = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void limpiar() {
        // Por si un test falla con la llamada retenida: se libera para no dejar hilos bloqueados.
        proveedor.liberar();
        hilos.shutdownNow();
    }

    /** Escenario de D10: la segunda confirmación llega mientras la primera espera al proveedor. */
    @Test
    void laSegundaConfirmacionRecibe409MientrasLaPrimeraEsperaAlProveedor() throws Exception {
        long id = crearPedido();

        Future<Integer> primera = hilos.submit(() -> confirmar(id));
        // La primera ya ha hecho la reserva (commit) y está retenida dentro del proveedor.
        assertThat(proveedor.esperarLlamada()).as("la primera confirmación llega al proveedor").isTrue();
        assertThat(estado(id)).isEqualTo("CONFIRMANDO");

        int segunda = confirmar(id);

        assertThat(segunda).isEqualTo(409);
        proveedor.liberar();
        assertThat(primera.get(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(proveedor.llamadas()).isEqualTo(1);
        assertPedidoConfirmado(id);
    }

    /**
     * Carrera real por el UPDATE condicional: 8 peticiones salen a la vez. El ganador queda retenido en el
     * proveedor hasta que los 7 perdedores han respondido, así que ninguno puede colarse después de que
     * el ganador termine.
     */
    @Test
    void deOchoConfirmacionesSimultaneasSoloUnaLlamaAlProveedor() throws Exception {
        long id = crearPedido();
        int peticiones = 8;
        CountDownLatch salida = new CountDownLatch(1);
        CompletionService<Integer> respuestas = new ExecutorCompletionService<>(hilos);
        for (int i = 0; i < peticiones; i++) {
            respuestas.submit(() -> {
                if (!salida.await(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("no se dio la salida");
                }
                return confirmar(id);
            });
        }

        salida.countDown();

        // Los 7 perdedores responden sin esperar al proveedor, mientras el ganador sigue retenido.
        List<Integer> perdedores = new ArrayList<>();
        for (int i = 0; i < peticiones - 1; i++) {
            perdedores.add(siguiente(respuestas));
        }
        assertThat(perdedores).containsOnly(409);
        // Un 409 solo garantiza que la reserva del ganador está confirmada, no que ya haya llegado al
        // proveedor: se espera a su llamada antes de contar.
        assertThat(proveedor.esperarLlamada()).as("el ganador llega al proveedor").isTrue();
        assertThat(proveedor.llamadas()).isEqualTo(1);

        proveedor.liberar();
        assertThat(siguiente(respuestas)).isEqualTo(200);
        assertThat(proveedor.llamadas()).isEqualTo(1);
        assertPedidoConfirmado(id);
    }

    private int confirmar(long id) throws Exception {
        return mockMvc.perform(post("/api/pedidos/{id}/confirmacion", id))
                .andReturn().getResponse().getStatus();
    }

    private static int siguiente(CompletionService<Integer> respuestas) throws Exception {
        Future<Integer> respuesta = respuestas.poll(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS);
        assertThat(respuesta).as("respuesta dentro del tiempo máximo").isNotNull();
        return respuesta.get();
    }

    private void assertPedidoConfirmado(long id) {
        Map<String, Object> fila = jdbcTemplate.queryForMap(
                "SELECT estado, coste_envio, importe_total FROM pedido WHERE id = ?", id);
        assertThat(fila).containsEntry("ESTADO", "CONFIRMADO")
                .containsEntry("COSTE_ENVIO", new BigDecimal("7.95"))
                .containsEntry("IMPORTE_TOTAL", new BigDecimal("105.44"));
    }

    private String estado(long id) {
        return jdbcTemplate.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, id);
    }

    // Pedido de 97,49 € de productos: confirmado con 7,95 € de envío, el total es 105,44 €.
    private long crearPedido() throws Exception {
        String respuesta = mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clienteId": 1,
                                  "codigoPostalDestino": "28001",
                                  "lineas": [
                                    {"producto": "Camiseta", "cantidad": 3, "precioUnitario": 12.50, "pesoUnitarioKg": 0.2},
                                    {"producto": "Zapatillas", "cantidad": 1, "precioUnitario": 59.99, "pesoUnitarioKg": 1.0}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(respuesta, "$.id")).longValue();
    }

    @TestConfiguration
    static class ConfiguracionProveedorFalso {

        // @Primary: sustituye al adaptador HTTP real solo en este test.
        @Bean
        @Primary
        ProveedorRetenido proveedorRetenido() {
            return new ProveedorRetenido();
        }
    }

    /**
     * Proveedor falso que cuenta las llamadas y retiene cada una hasta que el test la libera. Si el test
     * no la libera a tiempo, falla en lugar de quedarse bloqueado.
     */
    static class ProveedorRetenido implements ProveedorTarifas {

        private final AtomicInteger llamadas = new AtomicInteger();
        private volatile CountDownLatch llamadaRecibida;
        private volatile CountDownLatch liberacion;

        void reiniciar() {
            llamadas.set(0);
            llamadaRecibida = new CountDownLatch(1);
            liberacion = new CountDownLatch(1);
        }

        @Override
        public TarifaEnvio consultarTarifa(SolicitudTarifa solicitud) {
            llamadas.incrementAndGet();
            llamadaRecibida.countDown();
            try {
                if (!liberacion.await(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("el test no liberó la llamada al proveedor");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrumpido esperando la liberación", e);
            }
            return new TarifaEnvio(new BigDecimal("7.95"));
        }

        boolean esperarLlamada() throws InterruptedException {
            return llamadaRecibida.await(ESPERA_MAXIMA_SEGUNDOS, TimeUnit.SECONDS);
        }

        void liberar() {
            liberacion.countDown();
        }

        int llamadas() {
            return llamadas.get();
        }
    }
}

package com.empresa.pedidos.pedido.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/pedidos} de extremo a extremo: HTTP, validación, caso de uso y H2 real con Flyway.
 *
 * <p>Sin {@code @Transactional}: el caso de uso confirma su propia transacción, como en producción, y
 * lo persistido se comprueba con SQL directo. Cada test trabaja con el id que devuelve, así que los
 * datos de un test no interfieren con otro.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrearPedidoIntegracionTest {

    // Cliente de la migración de datos de demostración V2.
    private static final long CLIENTE_DEMO = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void creaElPedidoConSusLineasYDevuelve201() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peticion(CLIENTE_DEMO, "12.50")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/pedidos/\\d+")))
                .andExpect(jsonPath("$.estado").value("CREADO"))
                .andExpect(jsonPath("$.importeProductos").value(97.49))
                .andExpect(jsonPath("$.costeEnvio").doesNotExist())
                .andExpect(jsonPath("$.importeTotal").doesNotExist())
                .andExpect(jsonPath("$.fecha").isNotEmpty())
                .andExpect(jsonPath("$.lineas.length()").value(2))
                .andReturn();

        long id = ((Number) JsonPath.read(resultado.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(resultado.getResponse().getHeader("Location")).endsWith("/api/pedidos/" + id);

        Map<String, Object> pedido = jdbcTemplate.queryForMap(
                "SELECT cliente_id, estado, importe_productos, coste_envio FROM pedido WHERE id = ?", id);
        assertThat(((Number) pedido.get("CLIENTE_ID")).longValue()).isEqualTo(CLIENTE_DEMO);
        assertThat(pedido.get("ESTADO")).isEqualTo("CREADO");
        assertThat(pedido.get("IMPORTE_PRODUCTOS")).isEqualTo(new BigDecimal("97.49"));
        assertThat(pedido.get("COSTE_ENVIO")).isNull();

        Integer lineas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM linea_pedido WHERE pedido_id = ?", Integer.class, id);
        assertThat(lineas).isEqualTo(2);
    }

    @Test
    void rechazaUnPrecioConTresDecimalesCon400() throws Exception {
        long pedidosAntes = contarPedidos();

        // Con BigDecimal, @Digits cuenta la escala tal como llega: 12.505 y 12.500 se rechazan igual.
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peticion(CLIENTE_DEMO, "12.505")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.errores[0].campo").value("lineas[0].precioUnitario"));

        assertThat(contarPedidos()).isEqualTo(pedidosAntes);
    }

    @Test
    void rechazaUnPrecioConCerosFinalesDeMasCon400() throws Exception {
        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peticion(CLIENTE_DEMO, "12.500")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("lineas[0].precioUnitario"));
    }

    @Test
    void rechazaUnaCantidadConDecimalesCon400() throws Exception {
        long pedidosAntes = contarPedidos();
        // Sin accept-float-as-int: false, Jackson leería 1.5 como 1 y se crearía el pedido.
        String peticion = """
                {"clienteId": 1, "codigoPostalDestino": "28001", "lineas": [
                  {"producto": "Camiseta", "cantidad": 1.5, "precioUnitario": 12.50, "pesoUnitarioKg": 0.2}
                ]}
                """;

        mockMvc.perform(post("/api/pedidos").contentType(MediaType.APPLICATION_JSON).content(peticion))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        assertThat(contarPedidos()).isEqualTo(pedidosAntes);
    }

    @Test
    void rechazaUnaLineaNulaCon400() throws Exception {
        String peticion = """
                {"clienteId": 1, "codigoPostalDestino": "28001", "lineas": [null]}
                """;

        mockMvc.perform(post("/api/pedidos").contentType(MediaType.APPLICATION_JSON).content(peticion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("lineas[0]"));
    }

    @Test
    void rechazaUnClienteInexistenteCon422() throws Exception {
        long pedidosAntes = contarPedidos();

        mockMvc.perform(post("/api/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(peticion(999L, "12.50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.detail").value("No existe el cliente 999"));

        assertThat(contarPedidos()).isEqualTo(pedidosAntes);
    }

    private long contarPedidos() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pedido", Long.class);
    }

    // Caso del enunciado del paso: 3 camisetas a precioCamiseta y 0,2 kg + 1 zapatillas a 59,99 € y 1,0 kg.
    // Los números van como texto JSON literal para que el test controle exactamente los decimales.
    private static String peticion(long clienteId, String precioCamiseta) {
        return """
                {
                  "clienteId": %d,
                  "codigoPostalDestino": "28001",
                  "lineas": [
                    {"producto": "Camiseta", "cantidad": 3, "precioUnitario": %s, "pesoUnitarioKg": 0.2},
                    {"producto": "Zapatillas", "cantidad": 1, "precioUnitario": 59.99, "pesoUnitarioKg": 1.0}
                  ]
                }
                """.formatted(clienteId, precioCamiseta);
    }
}

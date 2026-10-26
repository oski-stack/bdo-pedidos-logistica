package com.empresa.pedidos.pedido.infrastructure.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code POST /api/pedidos/{id}/confirmacion} de extremo a extremo (D5, D6, D10): HTTP, caso de uso,
 * H2 real y el adaptador HTTP real contra un proveedor simulado con WireMock.
 *
 * <p>Sin {@code @Transactional}: el caso de uso gestiona sus propias transacciones, como en producción, y
 * el estado final se comprueba con SQL. Los timeouts son los cortos de
 * {@code src/test/resources/config/application.yml} (conexión 200 ms, lectura 500 ms).
 *
 * <p>{@code post(...)} es el de WireMock; las peticiones a nuestra API usan {@code MockMvcRequestBuilders.post}
 * explícitamente. La conexión rechazada se prueba en el test del adaptador: aquí no se puede parar WireMock,
 * porque al rearrancar cambiaría de puerto.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmarPedidoIntegracionTest {

    private static final String RUTA_PROVEEDOR = "/api/tarifas";
    private static final String TARIFA_OK = """
            {"coste": 7.95, "moneda": "EUR", "plazoDias": 2}
            """;

    // Se reinicia antes de cada test: stubs y peticiones registradas.
    @RegisterExtension
    static WireMockExtension proveedor = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void urlDelProveedor(DynamicPropertyRegistry registro) {
        registro.add("proveedor.url-base", proveedor::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void confirmaElPedidoConLaTarifaYEnviaElTokenYLosDatosDelPedido() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(200, TARIFA_OK)));
        long id = crearPedido();

        confirmar(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.costeEnvio").value(7.95))
                .andExpect(jsonPath("$.importeTotal").value(105.44))
                .andExpect(jsonPath("$.lineas.length()").value(2));

        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CONFIRMADO")
                .containsEntry("COSTE_ENVIO", new BigDecimal("7.95"))
                .containsEntry("IMPORTE_TOTAL", new BigDecimal("105.44"));
        // Token del config de test y datos calculados por el dominio (D1, D8).
        proveedor.verify(1, postRequestedFor(urlEqualTo(RUTA_PROVEEDOR))
                .withHeader("Authorization", equalTo("Bearer token-de-prueba"))
                .withRequestBody(equalToJson("""
                        {"codigoPostalDestino": "28001", "pesoKg": 1.600, "numBultos": 4}
                        """)));
    }

    @Test
    void unTimeoutDelProveedorDevuelve503SinReintentoYElPedidoVuelveACreado() throws Exception {
        // Retraso mayor que el timeout de lectura de los tests (500 ms).
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(200, TARIFA_OK).withFixedDelay(1_500)));
        long id = crearPedido();

        confirmar(id)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        assertThat(llamadasAlProveedor()).isEqualTo(1);
        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CREADO").containsEntry("COSTE_ENVIO", null);
    }

    @Test
    void un503SeguidoDeUn200SeRecuperaConDosLlamadas() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).inScenario("503 y recuperación")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recuperado"));
        proveedor.stubFor(post(RUTA_PROVEEDOR).inScenario("503 y recuperación")
                .whenScenarioStateIs("recuperado")
                .willReturn(json(200, TARIFA_OK)));
        long id = crearPedido();

        confirmar(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"));

        assertThat(llamadasAlProveedor()).isEqualTo(2);
        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CONFIRMADO");
    }

    @Test
    void un503PersistenteHaceDosLlamadasYDevuelve503() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(aResponse().withStatus(503)));
        long id = crearPedido();

        confirmar(id)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"));

        assertThat(llamadasAlProveedor()).isEqualTo(2);
        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CREADO");
    }

    @Test
    void trasUnFalloElPedidoSePuedeVolverAConfirmar() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(aResponse().withStatus(503)));
        long id = crearPedido();
        confirmar(id).andExpect(status().isServiceUnavailable());

        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(200, TARIFA_OK)));

        confirmar(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"));
    }

    @Test
    void un401DelProveedorDevuelve502SinReintentoNiDetallesDelProveedor() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(401, "{\"error\": \"detalle-interno-del-proveedor\"}")));
        long id = crearPedido();

        confirmar(id)
                .andExpect(status().isBadGateway())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                // El detail es fijo: ni el cuerpo ni el código del proveedor llegan al consumidor.
                .andExpect(content().string(not(containsString("detalle-interno-del-proveedor"))));

        assertThat(llamadasAlProveedor()).isEqualTo(1);
        proveedor.verify(postRequestedFor(urlEqualTo(RUTA_PROVEEDOR))
                .withHeader("Authorization", equalTo("Bearer token-de-prueba")));
        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CREADO");
    }

    @Test
    void unaRespuestaInvalidaDelProveedorDevuelve502() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(200, "{\"coste\": 7.95, \"moneda\": \"USD\"}")));
        long id = crearPedido();

        confirmar(id).andExpect(status().isBadGateway());

        assertThat(estadoEnBd(id)).containsEntry("ESTADO", "CREADO");
    }

    @Test
    void unPedidoYaConfirmadoDevuelve200SinVolverALlamarAlProveedor() throws Exception {
        proveedor.stubFor(post(RUTA_PROVEEDOR).willReturn(json(200, TARIFA_OK)));
        long id = crearPedido();
        confirmar(id).andExpect(status().isOk());

        confirmar(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.importeTotal").value(105.44));

        assertThat(llamadasAlProveedor()).isEqualTo(1);
    }

    @Test
    void unPedidoInexistenteDevuelve404SinLlamarAlProveedor() throws Exception {
        confirmar(999_999L)
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        assertThat(llamadasAlProveedor()).isZero();
    }

    private ResultActions confirmar(long id) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/pedidos/{id}/confirmacion", id));
    }

    // Pedido del caso de prueba: 97,49 € de productos, 1,600 kg y 4 bultos.
    private long crearPedido() throws Exception {
        String respuesta = mockMvc.perform(MockMvcRequestBuilders.post("/api/pedidos")
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

    private Map<String, Object> estadoEnBd(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT estado, coste_envio, importe_total FROM pedido WHERE id = ?", id);
    }

    private static int llamadasAlProveedor() {
        return proveedor.countRequestsMatching(postRequestedFor(urlEqualTo(RUTA_PROVEEDOR)).build()).getCount();
    }

    private static ResponseDefinitionBuilder json(int estado, String cuerpo) {
        return aResponse().withStatus(estado).withHeader("Content-Type", "application/json").withBody(cuerpo);
    }
}

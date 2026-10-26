package com.empresa.pedidos.pedido.infrastructure.proveedor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.empresa.pedidos.pedido.application.port.out.ErrorIntegracionProveedorException;
import com.empresa.pedidos.pedido.application.port.out.ProveedorNoDisponibleException;
import com.empresa.pedidos.pedido.domain.SolicitudTarifa;
import com.empresa.pedidos.pedido.domain.TarifaEnvio;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.web.client.ResourceAccessException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests aislados del adaptador HTTP contra un proveedor simulado con WireMock, sin contexto de Spring (D6, D10).
 *
 * <p>El adaptador se construye con los mismos métodos que usa {@link ConfiguracionProveedor}, así que se
 * prueba la configuración real con timeouts cortos. Las pruebas de extremo a extremo del endpoint de
 * confirmación quedan para el paso 5.
 */
class ProveedorTarifasHttpTest {

    private static final String RUTA = "/api/tarifas";
    private static final String TOKEN = "token-de-prueba";
    private static final SolicitudTarifa SOLICITUD = new SolicitudTarifa("43840", new BigDecimal("3.5"), 2);
    private static final String RESPUESTA_OK = """
            {"coste": 7.95, "moneda": "EUR", "plazoDias": 2}
            """;

    @RegisterExtension
    static WireMockExtension proveedor = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void enviaElTokenYElContratoYDevuelveLaTarifa() {
        responder(200, RESPUESTA_OK);

        TarifaEnvio tarifa = adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD);

        assertThat(tarifa.coste()).isEqualTo(new BigDecimal("7.95"));
        proveedor.verify(postRequestedFor(urlEqualTo(RUTA))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"codigoPostalDestino": "43840", "pesoKg": 3.5, "numBultos": 2}
                        """)));
    }

    @Test
    void reintentaUn503YSeRecuperaEnElSegundoIntento() {
        proveedor.stubFor(post(RUTA).inScenario("503 y recuperación")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recuperado"));
        proveedor.stubFor(post(RUTA).inScenario("503 y recuperación")
                .whenScenarioStateIs("recuperado")
                .willReturn(json(200, RESPUESTA_OK)));

        TarifaEnvio tarifa = adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD);

        assertThat(tarifa.coste()).isEqualTo(new BigDecimal("7.95"));
        assertThat(llamadas()).isEqualTo(2);
    }

    @Test
    void un503PersistenteAgotaLosDosIntentos() {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(llamadas()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {500, 502, 504})
    void otro5xxNoSeReintenta(int estado) {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withStatus(estado)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void unTimeoutDeRespuestaNoSeReintenta() {
        // Retraso mayor que el timeout de lectura del test (500 ms).
        proveedor.stubFor(post(RUTA).willReturn(json(200, RESPUESTA_OK).withFixedDelay(1_000)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class)
                .hasMessageContaining("Timeout");
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void unCuerpoQueLlegaMasLentoQueElTimeoutEsNoDisponibleSinReintento() {
        // Las cabeceras llegan enseguida; el cuerpo, en trozos durante 2 s (más que los 500 ms de lectura).
        proveedor.stubFor(post(RUTA).willReturn(json(200, RESPUESTA_OK).withChunkedDribbleDelay(5, 2_000)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void unCuerpoCortadoEsNoDisponibleSinReintento() {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void unaConexionCortadaAntesDeLasCabecerasEsNoDisponibleSinReintento() {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void unaRespuestaSinTipoDeContenidoJsonEsErrorDeIntegracion() {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>mantenimiento</html>")));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ErrorIntegracionProveedorException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {400, 401, 403, 404})
    void un4xxNoSeReintentaYEsErrorDeIntegracion(int estado) {
        proveedor.stubFor(post(RUTA).willReturn(aResponse().withStatus(estado)));

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ErrorIntegracionProveedorException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"moneda\": \"EUR\", \"plazoDias\": 2}",
            "{\"coste\": -1.00, \"moneda\": \"EUR\", \"plazoDias\": 2}",
            "{\"coste\": 7.955, \"moneda\": \"EUR\", \"plazoDias\": 2}",
            "{\"coste\": 7.95, \"moneda\": \"USD\", \"plazoDias\": 2}",
            "{\"coste\": 7.95, \"plazoDias\": 2}",
            "{\"coste\": \"siete\"}",
            "esto no es JSON"})
    void unaRespuestaInvalidaNoSeReintentaYEsErrorDeIntegracion(String cuerpo) {
        responder(200, cuerpo);

        assertThatThrownBy(() -> adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD))
                .isInstanceOf(ErrorIntegracionProveedorException.class);
        assertThat(llamadas()).isEqualTo(1);
    }

    @Test
    void aceptaCerosFinalesEnElCosteYLoDevuelveConDosDecimales() {
        responder(200, "{\"coste\": 7.950, \"moneda\": \"EUR\", \"plazoDias\": 2}");

        TarifaEnvio tarifa = adaptador(proveedor.baseUrl()).consultarTarifa(SOLICITUD);

        assertThat(tarifa.coste()).isEqualTo(new BigDecimal("7.95"));
    }

    @Test
    void unaConexionRechazadaSeReintentaYAcabaEnNoDisponible() throws IOException {
        ProveedorTarifasProperties propiedades = propiedades("http://localhost:" + puertoLibre());
        Retry retry = ConfiguracionProveedor.crearRetry(propiedades);
        AtomicInteger reintentos = new AtomicInteger();
        retry.getEventPublisher().onRetry(evento -> reintentos.incrementAndGet());
        ProveedorTarifasHttp adaptador = new ProveedorTarifasHttp(ConfiguracionProveedor.crearRestClient(propiedades), retry);

        assertThatThrownBy(() -> adaptador.consultarTarifa(SOLICITUD))
                .isInstanceOf(ProveedorNoDisponibleException.class);
        // 1 reintento = 2 intentos en total.
        assertThat(reintentos).hasValue(1);
    }

    @Test
    void unTimeoutDeConexionSeReintentaAunqueHeredeDelTimeoutDelIntercambio() {
        // HttpConnectTimeoutException hereda de HttpTimeoutException: el orden de las comprobaciones importa.
        assertThat(new HttpConnectTimeoutException("conexión")).isInstanceOf(HttpTimeoutException.class);

        // No se prueba contra una IP que no enruta: el resultado dependería de la red.
        assertThat(traducir(new HttpConnectTimeoutException("conexión"))).isInstanceOf(FalloRecuperableException.class);
        assertThat(traducir(new ConnectException("rechazada"))).isInstanceOf(FalloRecuperableException.class);
        assertThat(traducir(new HttpTimeoutException("intercambio"))).isInstanceOf(ProveedorNoDisponibleException.class);
        assertThat(traducir(new IOException("conexión cortada"))).isInstanceOf(ProveedorNoDisponibleException.class);
    }

    private static RuntimeException traducir(IOException causa) {
        return ProveedorTarifasHttp.traducirErrorDeEntradaSalida(new ResourceAccessException("E/S", causa));
    }

    private static ProveedorTarifasHttp adaptador(String urlBase) {
        ProveedorTarifasProperties propiedades = propiedades(urlBase);
        return new ProveedorTarifasHttp(
                ConfiguracionProveedor.crearRestClient(propiedades), ConfiguracionProveedor.crearRetry(propiedades));
    }

    // Timeouts cortos y sin jitter para que la suite sea rápida y determinista.
    private static ProveedorTarifasProperties propiedades(String urlBase) {
        return new ProveedorTarifasProperties(
                URI.create(urlBase), TOKEN, Duration.ofMillis(200), Duration.ofMillis(500),
                new ProveedorTarifasProperties.Reintentos(2, Duration.ofMillis(10), 1.0, 0.0));
    }

    private static void responder(int estado, String cuerpo) {
        proveedor.stubFor(post(RUTA).willReturn(json(estado, cuerpo)));
    }

    private static ResponseDefinitionBuilder json(int estado, String cuerpo) {
        return aResponse().withStatus(estado).withHeader("Content-Type", "application/json").withBody(cuerpo);
    }

    private static int llamadas() {
        return proveedor.countRequestsMatching(postRequestedFor(urlEqualTo(RUTA)).build()).getCount();
    }

    // Puerto en el que nadie escucha: se abre y se cierra un socket para obtenerlo.
    private static int puertoLibre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

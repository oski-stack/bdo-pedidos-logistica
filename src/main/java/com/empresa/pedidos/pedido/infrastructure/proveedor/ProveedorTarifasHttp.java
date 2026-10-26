package com.empresa.pedidos.pedido.infrastructure.proveedor;

import com.empresa.pedidos.pedido.application.port.out.ErrorIntegracionProveedorException;
import com.empresa.pedidos.pedido.application.port.out.ProveedorNoDisponibleException;
import com.empresa.pedidos.pedido.application.port.out.ProveedorTarifas;
import com.empresa.pedidos.pedido.domain.SolicitudTarifa;
import com.empresa.pedidos.pedido.domain.TarifaEnvio;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptador HTTP del puerto {@link ProveedorTarifas}: {@code POST /api/tarifas} con {@code RestClient},
 * reintentos con Resilience4j y validación de la respuesta (D5, D6).
 *
 * <p>Clasificación de fallos (D6):
 * <ul>
 *   <li>503, o error de conexión antes de enviar la petición → se reintenta; agotados los intentos,
 *       {@link ProveedorNoDisponibleException}.</li>
 *   <li>Timeout, otro 5xx u otro error de E/S (también un corte o un timeout mientras llega el cuerpo)
 *       → {@link ProveedorNoDisponibleException}, sin reintentar.</li>
 *   <li>4xx o respuesta que no cumple el contrato → {@link ErrorIntegracionProveedorException}, sin
 *       reintentar.</li>
 * </ul>
 * Nunca se registra el token ni el cuerpo de las respuestas de error: solo el código de estado, el tipo
 * de la causa o el motivo.
 */
class ProveedorTarifasHttp implements ProveedorTarifas {

    private static final Logger log = LoggerFactory.getLogger(ProveedorTarifasHttp.class);
    private static final String MONEDA_ESPERADA = "EUR";

    private final RestClient restClient;
    private final Retry retry;

    ProveedorTarifasHttp(RestClient restClient, Retry retry) {
        this.restClient = restClient;
        this.retry = retry;
    }

    @Override
    public TarifaEnvio consultarTarifa(SolicitudTarifa solicitud) {
        TarifaRequest peticion = TarifaRequest.desde(solicitud);
        // Se cuentan los intentos reales: si se interrumpe el hilo durante la espera, Resilience4j
        // abandona sin agotar los configurados.
        AtomicInteger intentos = new AtomicInteger();
        try {
            // El Retry solo vuelve a ejecutar el intento si lanza FalloRecuperableException.
            TarifaResponse respuesta = retry.executeSupplier(() -> {
                intentos.incrementAndGet();
                return intentar(peticion);
            });
            return validar(respuesta);
        } catch (FalloRecuperableException agotado) {
            log.warn("Proveedor de tarifas no disponible tras {} intento(s): {}", intentos.get(), agotado.getMessage());
            throw new ProveedorNoDisponibleException("Proveedor de tarifas no disponible", agotado);
        }
    }

    // Un intento: traduce cada fallo a recuperable (se reintenta) o definitivo (no se reintenta).
    private TarifaResponse intentar(TarifaRequest peticion) {
        try {
            return restClient.post()
                    .uri("/api/tarifas")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(peticion)
                    .retrieve()
                    .body(TarifaResponse.class);
        } catch (HttpServerErrorException e) {
            // Solo el 503 exacto: el proveedor avisa de que no puede atender ahora (D6).
            if (e.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
                throw new FalloRecuperableException("503 del proveedor", e);
            }
            log.warn("El proveedor de tarifas respondió {}", e.getStatusCode().value());
            throw new ProveedorNoDisponibleException("El proveedor de tarifas respondió " + e.getStatusCode().value(), e);
        } catch (HttpClientErrorException e) {
            // 401/403 u otro 4xx: error de configuración de nuestra integración, no del consumidor (D5).
            log.error("El proveedor de tarifas rechazó la petición con {}", e.getStatusCode().value());
            throw new ErrorIntegracionProveedorException("El proveedor de tarifas rechazó la petición", e);
        } catch (ResourceAccessException e) {
            throw traducirErrorDeEntradaSalida(e);
        } catch (RestClientException e) {
            if (esCorteDuranteElCuerpo(e)) {
                // Las cabeceras llegaron pero el cuerpo se cortó o venció el timeout: fallo transitorio,
                // no un contrato roto. La petición llegó al proveedor, así que no se reintenta (D6).
                log.warn("Respuesta del proveedor de tarifas interrumpida: {}", tipoDeCausa(e));
                throw new ProveedorNoDisponibleException("Respuesta del proveedor de tarifas interrumpida", e);
            }
            // Cuerpo que no es JSON válido, tipo de contenido inesperado, etc.
            log.error("Respuesta ilegible del proveedor de tarifas: {}", tipoDeCausa(e));
            throw new ErrorIntegracionProveedorException("Respuesta ilegible del proveedor de tarifas", e);
        }
    }

    /**
     * Spring envuelve en un {@code RestClientException} genérico (no en {@code ResourceAccessException})
     * cualquier {@code IOException} producida al leer el cuerpo. Un error de Jackson también es
     * {@code IOException}, pero significa que el cuerpo llegó y no es JSON válido: eso sí es un 502.
     */
    static boolean esCorteDuranteElCuerpo(RestClientException e) {
        Throwable causa = e.getCause() instanceof UncheckedIOException unchecked ? unchecked.getCause() : e.getCause();
        return causa instanceof IOException && !(causa instanceof JsonProcessingException);
    }

    // Solo el tipo: los mensajes de estas excepciones incluyen la URL o el cuerpo de la respuesta.
    private static String tipoDeCausa(Exception e) {
        return e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName();
    }

    /** Package-private para poder probar el orden de las comprobaciones. */
    static RuntimeException traducirErrorDeEntradaSalida(ResourceAccessException e) {
        if (esFalloDeConexion(e.getCause())) {
            return new FalloRecuperableException("Error de conexión con el proveedor", e);
        }
        if (e.getCause() instanceof HttpTimeoutException) {
            // Timeout del intercambio: ya se ha consumido el tiempo de un intento, no se reintenta (D6).
            log.warn("Timeout esperando la respuesta del proveedor de tarifas");
            return new ProveedorNoDisponibleException("Timeout del proveedor de tarifas", e);
        }
        // Otro error de E/S (p. ej. conexión cortada antes de las cabeceras): la petición pudo llegar,
        // no se reintenta.
        log.warn("Error de E/S con el proveedor de tarifas: {}", tipoDeCausa(e));
        return new ProveedorNoDisponibleException("Error de comunicación con el proveedor de tarifas", e);
    }

    /**
     * Fallos en los que la petición no llegó a enviarse: conexión rechazada, host no resoluble o timeout de
     * conexión. El cliente del JDK entrega el host no resoluble como {@code ConnectException} con causa
     * {@code UnresolvedAddressException}. {@code HttpConnectTimeoutException} hereda de
     * {@code HttpTimeoutException}, que es el timeout del intercambio y no se reintenta: por eso se
     * comprueba aquí, antes que el caso general.
     */
    static boolean esFalloDeConexion(Throwable causa) {
        return causa instanceof HttpConnectTimeoutException || causa instanceof ConnectException;
    }

    // Validación del contrato de respuesta (D5). Se hace fuera del Retry: una respuesta inválida no se reintenta.
    private static TarifaEnvio validar(TarifaResponse respuesta) {
        if (respuesta == null || respuesta.coste() == null) {
            throw respuestaInvalida("sin coste");
        }
        BigDecimal coste = respuesta.coste();
        if (coste.signum() < 0) {
            throw respuestaInvalida("coste negativo");
        }
        // Se ignoran los ceros finales: 7.950 es exactamente 7.95. Rechazarlo cortaría las confirmaciones
        // sin proteger ningún dato; un tercer decimal significativo sí obligaría a redondear.
        if (coste.stripTrailingZeros().scale() > 2) {
            throw respuestaInvalida("coste con más de 2 decimales");
        }
        if (!MONEDA_ESPERADA.equals(respuesta.moneda())) {
            throw respuestaInvalida("moneda distinta de EUR");
        }
        return new TarifaEnvio(coste.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static ErrorIntegracionProveedorException respuestaInvalida(String motivo) {
        log.error("Respuesta inválida del proveedor de tarifas: {}", motivo);
        return new ErrorIntegracionProveedorException("Respuesta inválida del proveedor de tarifas: " + motivo);
    }
}

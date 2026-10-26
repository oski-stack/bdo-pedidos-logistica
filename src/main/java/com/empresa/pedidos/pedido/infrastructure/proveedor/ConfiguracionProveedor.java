package com.empresa.pedidos.pedido.infrastructure.proveedor;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Construye el cliente HTTP y la política de reintentos del proveedor a partir de
 * {@link ProveedorTarifasProperties} (D6, D8).
 *
 * <p>Los métodos estáticos {@code crear*} los usan también los tests del adaptador, para probar
 * exactamente la misma configuración sin levantar el contexto de Spring.
 */
@Configuration
@EnableConfigurationProperties(ProveedorTarifasProperties.class)
public class ConfiguracionProveedor {

    static final String NOMBRE_RETRY = "proveedorTarifas";

    @Bean
    ProveedorTarifasHttp proveedorTarifasHttp(ProveedorTarifasProperties propiedades) {
        return new ProveedorTarifasHttp(crearRestClient(propiedades), crearRetry(propiedades));
    }

    /**
     * Cliente HTTP del JDK: distingue por tipo el timeout de conexión ({@code HttpConnectTimeoutException})
     * del resto ({@code HttpTimeoutException}), y eso decide si se reintenta (D6).
     * {@code HttpURLConnection} lanza {@code SocketTimeoutException} en ambos casos.
     *
     * <p>Ojo: en Spring 6.2 el "timeout de lectura" de {@code JdkClientHttpRequestFactory} es un límite de
     * todo el intercambio (conexión, envío, cabeceras y cuerpo), no solo de la espera de la respuesta. Por
     * eso {@link ProveedorTarifasProperties} exige que sea mayor que el de conexión. El cliente no sigue
     * redirecciones (valor por defecto del JDK), así que el token no se reenvía a otro host.
     */
    static RestClient crearRestClient(ProveedorTarifasProperties propiedades) {
        HttpClient httpClient = HttpClient.newBuilder()
                // HTTP/1.1 explícito: evita el intento de upgrade a HTTP/2 en conexiones http://.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(propiedades.timeoutConexion())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(propiedades.timeoutLectura());

        return RestClient.builder()
                .baseUrl(propiedades.urlBase().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + propiedades.token())
                .build();
    }

    /**
     * Reintentos con backoff exponencial y jitter. Solo se reintenta {@link FalloRecuperableException}:
     * qué es recuperable lo decide el código del adaptador, no la configuración.
     */
    static Retry crearRetry(ProveedorTarifasProperties propiedades) {
        ProveedorTarifasProperties.Reintentos reintentos = propiedades.reintentos();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(reintentos.maxIntentos())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        reintentos.esperaInicial(), reintentos.multiplicador(), reintentos.jitter()))
                .retryOnException(FalloRecuperableException.class::isInstance)
                .build();
        return Retry.of(NOMBRE_RETRY, config);
    }
}

package com.empresa.pedidos.pedido.infrastructure.proveedor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del proveedor de tarifas, externalizada en {@code application.yml} (D8).
 *
 * <p>{@code @Validated}: si un valor falta o no es válido, la aplicación no arranca. En particular el
 * token, que se lee de la variable de entorno {@code PROVEEDOR_TOKEN} y nunca está en el código.
 *
 * <p>Las comprobaciones que dependen del token o de varios campos son métodos {@code @AssertTrue}: si
 * fallan, Spring informa del valor {@code false}, no del valor rechazado, y el token no aparece en el
 * error de arranque. Con {@code @Pattern} sobre el token se imprimiría el valor.
 *
 * @param urlBase         URL base del proveedor, sin {@code /api/tarifas}
 * @param token           token Bearer
 * @param timeoutConexion tiempo máximo para establecer la conexión, por intento (D6)
 * @param timeoutLectura  tiempo máximo de todo el intercambio HTTP, por intento (D6)
 * @param reintentos      política de reintentos (D6)
 */
@Validated
@ConfigurationProperties(prefix = "proveedor")
public record ProveedorTarifasProperties(
        @NotNull URI urlBase,
        @NotBlank(message = "falta el token del proveedor: define la variable de entorno PROVEEDOR_TOKEN") String token,
        @NotNull @DurationMin(millis = 1) Duration timeoutConexion,
        @NotNull @DurationMin(millis = 1) Duration timeoutLectura,
        @NotNull @Valid Reintentos reintentos) {

    // b64token de RFC 6750. Un salto de línea final (habitual en secretos leídos de fichero) haría que el
    // JDK rechazara la cabecera con una excepción cuyo mensaje incluye el token completo.
    private static final Pattern FORMATO_TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");
    private static final Set<String> ESQUEMAS_PERMITIDOS = Set.of("http", "https");

    /**
     * @param maxIntentos   intentos totales, incluido el primero
     * @param esperaInicial espera antes del primer reintento
     * @param multiplicador factor de crecimiento exponencial de la espera
     * @param jitter        aleatoriedad de la espera, en [0, 1): Resilience4j no admite 1 (0,5 → ±50 %)
     */
    public record Reintentos(
            @Min(1) int maxIntentos,
            @NotNull @DurationMin(millis = 1) Duration esperaInicial,
            @DecimalMin("1.0") double multiplicador,
            @DecimalMin("0.0") @DecimalMax(value = "1.0", inclusive = false) double jitter) {
    }

    @AssertTrue(message = "PROVEEDOR_TOKEN contiene caracteres no válidos (¿un salto de línea al final?)")
    public boolean isTokenConFormatoValido() {
        // Si es nulo o vacío ya lo informa @NotBlank.
        return token == null || token.isBlank() || FORMATO_TOKEN.matcher(token).matches();
    }

    /**
     * Con {@code JdkClientHttpRequestFactory}, el timeout de lectura cubre todo el intercambio, incluida
     * la conexión. Si no es mayor que el de conexión, un timeout de conexión llegaría como timeout de
     * respuesta y dejaría de reintentarse (D6).
     */
    @AssertTrue(message = "el timeout de lectura debe ser mayor que el de conexión")
    public boolean isTimeoutLecturaMayorQueConexion() {
        return timeoutConexion == null || timeoutLectura == null || timeoutLectura.compareTo(timeoutConexion) > 0;
    }

    /** Solo http/https y sin credenciales en la URL: acabarían en los mensajes de error de E/S. */
    @AssertTrue(message = "la URL del proveedor debe ser http(s) y no incluir credenciales")
    public boolean isUrlBaseValida() {
        return urlBase == null
                || (urlBase.getScheme() != null && ESQUEMAS_PERMITIDOS.contains(urlBase.getScheme().toLowerCase())
                        && urlBase.getUserInfo() == null);
    }

    /** El toString de un record incluiría el token: se oculta para que nunca acabe en un log (D8). */
    @Override
    public String toString() {
        return "ProveedorTarifasProperties[urlBase=" + urlBase + ", token=***, timeoutConexion=" + timeoutConexion
                + ", timeoutLectura=" + timeoutLectura + ", reintentos=" + reintentos + "]";
    }
}

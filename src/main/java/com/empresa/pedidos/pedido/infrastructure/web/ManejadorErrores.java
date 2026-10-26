package com.empresa.pedidos.pedido.infrastructure.web;

import com.empresa.pedidos.pedido.application.ClienteNoEncontradoException;
import com.empresa.pedidos.pedido.application.ConfirmacionEnCursoException;
import com.empresa.pedidos.pedido.application.PedidoNoEncontradoException;
import com.empresa.pedidos.pedido.application.port.out.ErrorIntegracionProveedorException;
import com.empresa.pedidos.pedido.application.port.out.ProveedorNoDisponibleException;
import com.empresa.pedidos.pedido.domain.PedidoInvalidoException;
import com.empresa.pedidos.pedido.domain.TransicionInvalidaException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce las excepciones a respuestas {@link ProblemDetail} (RFC 9457) según D2 y D5.
 *
 * <p>Hereda de {@link ResponseEntityExceptionHandler} para que los errores estándar de Spring MVC
 * (JSON mal formado, método no soportado, etc.) también salgan como ProblemDetail. Los cuerpos no
 * incluyen trazas ni detalles internos.
 *
 * <p>Los errores del proveedor llevan un {@code detail} fijo: sus mensajes y causas pueden contener el
 * cuerpo de la respuesta del proveedor o motivos internos, que no son para el consumidor. El adaptador
 * ya los registra; aquí no se vuelven a registrar.
 */
@RestControllerAdvice
class ManejadorErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    // Segundos que se sugiere esperar antes de reintentar la confirmación (D5).
    static final String SEGUNDOS_REINTENTO = "30";

    /** 400 con la lista de campos inválidos, para que el consumidor sepa qué corregir. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "La petición no es válida");
        List<Map<String, String>> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("campo", e.getField(), "mensaje", String.valueOf(e.getDefaultMessage())))
                .toList();
        problema.setProperty("errores", errores);
        return ResponseEntity.badRequest().headers(headers).body(problema);
    }

    /** 422: la petición es correcta, pero el cliente referenciado no existe (D2). */
    @ExceptionHandler(ClienteNoEncontradoException.class)
    ProblemDetail clienteNoEncontrado(ClienteNoEncontradoException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /** 400: red de seguridad si unos datos inválidos pasan Bean Validation y los rechaza el dominio. */
    @ExceptionHandler(PedidoInvalidoException.class)
    ProblemDetail pedidoInvalido(PedidoInvalidoException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 404: el pedido que se quiere confirmar no existe (D5). */
    @ExceptionHandler(PedidoNoEncontradoException.class)
    ProblemDetail pedidoNoEncontrado(PedidoNoEncontradoException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 409: otra petición está confirmando el pedido, o el estado no permite la transición (D3, D5). */
    @ExceptionHandler({ConfirmacionEnCursoException.class, TransicionInvalidaException.class})
    ProblemDetail conflictoDeEstado(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 503 + Retry-After: fallo transitorio del proveedor; el pedido ha vuelto a CREADO (D5). */
    @ExceptionHandler(ProveedorNoDisponibleException.class)
    ResponseEntity<ProblemDetail> proveedorNoDisponible(ProveedorNoDisponibleException ex) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El proveedor logístico no está disponible. Inténtalo de nuevo más tarde.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, SEGUNDOS_REINTENTO)
                .body(problema);
    }

    /**
     * 502: la integración con el proveedor está rota (4xx o respuesta inválida). Es un problema nuestro,
     * no del consumidor; el pedido ha vuelto a CREADO (D5).
     */
    @ExceptionHandler(ErrorIntegracionProveedorException.class)
    ProblemDetail errorIntegracionProveedor(ErrorIntegracionProveedorException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Error en la integración con el proveedor logístico.");
    }

    /** 500 para cualquier otra excepción: se registra con la traza y el consumidor recibe un detalle genérico. */
    @ExceptionHandler(Exception.class)
    ProblemDetail errorInesperado(Exception ex) {
        log.error("Error no controlado", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno.");
    }
}

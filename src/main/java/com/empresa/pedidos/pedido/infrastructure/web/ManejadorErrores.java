package com.empresa.pedidos.pedido.infrastructure.web;

import com.empresa.pedidos.pedido.application.ClienteNoEncontradoException;
import com.empresa.pedidos.pedido.domain.PedidoInvalidoException;
import java.util.List;
import java.util.Map;
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
 * Traduce las excepciones a respuestas {@link ProblemDetail} (RFC 9457).
 *
 * <p>Hereda de {@link ResponseEntityExceptionHandler} para que los errores estándar de Spring MVC
 * (JSON mal formado, método no soportado, etc.) también salgan como ProblemDetail. Los cuerpos no
 * incluyen trazas ni detalles internos.
 */
@RestControllerAdvice
class ManejadorErrores extends ResponseEntityExceptionHandler {

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
}

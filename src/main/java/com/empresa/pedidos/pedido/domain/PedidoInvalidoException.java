package com.empresa.pedidos.pedido.domain;

/**
 * Se lanza cuando un pedido o una de sus líneas incumple una regla del dominio (D1, D2, D7).
 *
 * <p>La capa web valida la entrada antes con Bean Validation; esta excepción es la última línea de
 * defensa para que el dominio nunca quede en un estado inválido, venga de donde venga la llamada.
 */
public class PedidoInvalidoException extends RuntimeException {

    public PedidoInvalidoException(String mensaje) {
        super(mensaje);
    }
}

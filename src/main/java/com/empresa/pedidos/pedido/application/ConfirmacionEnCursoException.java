package com.empresa.pedidos.pedido.application;

/**
 * Otra petición tiene reservado el pedido para confirmarlo: esta no llama al proveedor y la API
 * responde 409 (D4, D5).
 */
public class ConfirmacionEnCursoException extends RuntimeException {

    public ConfirmacionEnCursoException(Long pedidoId) {
        super("El pedido " + pedidoId + " tiene una confirmación en curso");
    }
}

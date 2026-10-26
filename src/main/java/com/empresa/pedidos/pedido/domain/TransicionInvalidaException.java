package com.empresa.pedidos.pedido.domain;

/**
 * Se ha intentado una transición de {@link EstadoPedido} que no permite el diagrama de D3; por ejemplo,
 * confirmar un pedido que no está reservado. Es un conflicto de estado, no un dato de entrada inválido:
 * la API responde 409.
 */
public class TransicionInvalidaException extends RuntimeException {

    public TransicionInvalidaException(String mensaje) {
        super(mensaje);
    }
}

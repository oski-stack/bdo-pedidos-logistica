package com.empresa.pedidos.pedido.application;

/** El pedido que se quiere confirmar no existe: la API responde 404 (D5). */
public class PedidoNoEncontradoException extends RuntimeException {

    public PedidoNoEncontradoException(Long pedidoId) {
        super("No existe el pedido " + pedidoId);
    }
}

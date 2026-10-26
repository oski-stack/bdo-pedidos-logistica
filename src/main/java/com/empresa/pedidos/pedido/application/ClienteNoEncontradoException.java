package com.empresa.pedidos.pedido.application;

/**
 * El cliente indicado al crear un pedido no existe. La capa web la traduce a 422: el recurso que se
 * crea es el pedido, así que un 404 sería confuso (D2).
 */
public class ClienteNoEncontradoException extends RuntimeException {

    public ClienteNoEncontradoException(Long clienteId) {
        super("No existe el cliente " + clienteId);
    }
}

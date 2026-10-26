package com.empresa.pedidos.pedido.infrastructure.proveedor;

/**
 * Fallo que se puede reintentar: un 503 o un error de conexión, es decir, la petición no llegó a
 * procesarse (D6). Es la única excepción que reintenta el {@code Retry} del adaptador.
 *
 * <p>Es interna del adaptador: si se agotan los reintentos se traduce a
 * {@code ProveedorNoDisponibleException}, y la aplicación nunca la ve.
 */
class FalloRecuperableException extends RuntimeException {

    FalloRecuperableException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}

package com.empresa.pedidos.pedido.application.port.out;

/**
 * El proveedor de tarifas no está disponible: timeout, 5xx o error de conexión, después de los
 * reintentos que correspondan (D6). Es un fallo transitorio del proveedor; nuestra API responde 503 (D5).
 */
public class ProveedorNoDisponibleException extends RuntimeException {

    public ProveedorNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}

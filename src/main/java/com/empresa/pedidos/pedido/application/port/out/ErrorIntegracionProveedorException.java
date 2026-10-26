package com.empresa.pedidos.pedido.application.port.out;

/**
 * La integración con el proveedor de tarifas está rota: rechaza la petición (4xx, por ejemplo un token
 * inválido) o devuelve una respuesta que no cumple el contrato. No es culpa del consumidor de nuestra
 * API ni se arregla reintentando; nuestra API responde 502 (D5).
 */
public class ErrorIntegracionProveedorException extends RuntimeException {

    public ErrorIntegracionProveedorException(String mensaje) {
        super(mensaje);
    }

    public ErrorIntegracionProveedorException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}

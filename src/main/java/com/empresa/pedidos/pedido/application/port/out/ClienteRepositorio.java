package com.empresa.pedidos.pedido.application.port.out;

/**
 * Lo único que el módulo de pedidos necesita saber de los clientes: si existen (D2).
 */
public interface ClienteRepositorio {

    boolean existe(Long id);
}

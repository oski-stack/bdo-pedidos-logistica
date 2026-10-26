package com.empresa.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada del servicio de pedidos.
 *
 * <p>Monolito con arquitectura hexagonal ligera y paquetes por funcionalidad ({@code pedido},
 * {@code cliente}); ver D2 en {@code docs/decisiones.md}.
 */
@SpringBootApplication
public class PedidosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PedidosApplication.class, args);
    }
}

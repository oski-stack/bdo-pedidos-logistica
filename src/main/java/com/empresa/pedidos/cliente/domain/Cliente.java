package com.empresa.pedidos.cliente.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Cliente que realiza pedidos.
 *
 * <p>Para este alcance basta con comprobar que el cliente existe al crear un pedido. {@code Pedido}
 * solo guarda su identificador (D2). No hay endpoint para crear clientes: los de demostración se
 * cargan con la migración {@code V2__clientes_demostracion.sql} (D9).
 */
@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nombre;

    // 254 caracteres: longitud máxima práctica de una dirección de correo (RFC 5321).
    @Column(nullable = false, length = 254, unique = true)
    private String email;

    protected Cliente() {
        // Requerido por JPA.
    }

    public Cliente(String nombre, String email) {
        this.nombre = nombre;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getEmail() {
        return email;
    }
}

package com.empresa.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Arranca el contexto completo: con ddl-auto=validate, si las migraciones de Flyway
 * y las entidades JPA no cuadran, el contexto no levanta y el test falla.
 *
 * <p>Además comprueba que se aplica la migración de datos de demostración (D9).
 */
@SpringBootTest
class ArranqueAplicacionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void arrancaConLosClientesDeDemostracion() {
        Integer clientes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cliente", Integer.class);

        assertThat(clientes).isEqualTo(2);
    }
}

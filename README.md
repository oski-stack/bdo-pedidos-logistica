# Pedidos con integración logística

Proyecto inicial para la prueba técnica Java Senior. En este primer paso contiene el esqueleto Spring Boot, las entidades iniciales y el esquema H2 gestionado por Flyway. Los endpoints se implementarán en los siguientes pasos.

## Requisitos

- JDK 17
- Maven 3.8+

## Arranque

```bash
mvn spring-boot:run
```

La aplicación usa H2 en memoria. Flyway crea las tablas y carga dos clientes de demostración al arrancar.

## Estado actual

Este es el esqueleto inicial. Todavía no expone endpoints ni integra el proveedor logístico.

Las decisiones de diseño y los supuestos están en [`docs/decisiones.md`](docs/decisiones.md). El registro de uso de IA está en [`docs/registro-ia.md`](docs/registro-ia.md).

## Revisión asistida por agentes

La configuración de Claude Code para la revisión asistida por agentes está descrita en [`docs/orquestacion.md`](docs/orquestacion.md).

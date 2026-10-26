# CLAUDE.md — Instrucciones para Claude Code

## Contexto
Prueba técnica para Java Senior: servicio de pedidos con integración con un proveedor logístico externo.
**Las decisiones están en `docs/decisiones.md` y son la fuente de verdad.** Impleméntalas tal cual. Si una es inviable o mejorable, dilo y espera respuesta; no la cambies por tu cuenta.

El autor defenderá cada línea en una revisión técnica: prioriza código claro y explicable frente a código ingenioso.

## Stack
Java 17 · Maven · Spring Boot **3.2 o superior** (por `RestClient`) · Spring Data JPA · H2 · Flyway · Bean Validation · Resilience4j (`resilience4j-spring-boot3`) · JUnit 5 · AssertJ · WireMock (`org.wiremock:wiremock-standalone`)

## Arquitectura (resumen; detalle en D2)
```
com.empresa.pedidos
├── pedido/
│   ├── domain/                Pedido (raíz), LineaPedido, EstadoPedido, TarifaEnvio, SolicitudTarifa, excepciones
│   ├── application/
│   │   ├── port/out/          PedidoRepositorio, ClienteRepositorio, ProveedorTarifas
│   │   ├── CrearPedidoUseCase
│   │   └── ConfirmarPedidoUseCase
│   └── infrastructure/
│       ├── web/               controllers, DTOs (records), @RestControllerAdvice
│       ├── persistence/       adaptadores de los puertos con Spring Data JPA
│       └── proveedor/         adaptador RestClient + Resilience4j + @ConfigurationProperties
└── cliente/                   entidad Cliente y su persistencia
```
Reglas de dependencia:
- `domain` no depende de Spring, de `application` ni de `infrastructure` (solo `jakarta.persistence`).
- `application` depende solo de `domain` y de sus propios puertos. **Nunca** de `infrastructure`, `JpaRepository`, `RestClient` ni anotaciones web.
- Todos los puertos de salida viven en `application/port/out`. No crear puertos en otra ubicación.
- `Pedido` guarda `clienteId`, no una relación JPA con `Cliente`.

## Trampas a evitar
- **NO** marcar el flujo de confirmación completo con `@Transactional`: la llamada al proveedor va sin transacción (D4).
- **NO** usar `@Transactional` en métodos privados ni en autoinvocaciones: el proxy de Spring no los intercepta. Usa `TransactionTemplate` o beans separados.
- **NO** usar `synchronized`, locks en memoria ni `SELECT ... FOR UPDATE`.
- **NO** confiar en que `@Version` cubra un UPDATE masivo: `reservarParaConfirmacion` incrementa la versión explícitamente y devuelve si se modificó exactamente una fila (D4).
- **NO** usar `double`/`float` para importes ni pesos.
- **NO** reintentar tras timeout de lectura ni ante 4xx (D6).
- **NO** poner `@Transactional` en el test de concurrencia (D10).
- **NO** usar `Thread.sleep` en los tests; las esperas con `CountDownLatch` siempre llevan timeout.

## Forma de trabajar
1. Un paso del plan cada vez. No avances sin confirmación.
2. Antes de escribir código en un paso, resume en 3–5 líneas qué vas a hacer.
3. Al terminar cada paso, ejecuta la verificación (abajo), resume qué has hecho y por qué, y propón un mensaje de commit (Conventional Commits, en español). **No hagas tú el commit.**
4. Si propones algo no trivial, menciona la alternativa descartada.

## Verificación por paso
- `mvn verify` pasa sin tests ignorados ni desactivados.
- Ningún secreto en el código ni en `application.yml` (el token se lee de `PROVEEDOR_TOKEN`).
- Se respetan las reglas de dependencia entre paquetes.
- Si el paso toca una decisión de `docs/decisiones.md`, indica cuál y confirma que se cumple.

## Plan de pasos
1. Esqueleto Maven/Spring Boot, entidades y migraciones Flyway (esquema + clientes de demostración).
2. Puertos `PedidoRepositorio` y `ClienteRepositorio` con sus adaptadores. `POST /api/pedidos` con cálculo de importes en el dominio, validaciones (D1, D2, D7) y test unitario del total.
3. Puerto `ProveedorTarifas` + adaptador HTTP (timeouts, token, reintentos, validación de la respuesta) (D6, D8).
4. `POST /api/pedidos/{id}/confirmacion`: reserva con `reservarParaConfirmacion`, llamada, finalización/liberación (D4, D5).
5. Tests de integración con WireMock (D10).
6. Test de concurrencia con base de datos real y proveedor falso con latch (D10).
7. README: cómo arrancar la app y los tests; decisiones (estados, resiliencia, concurrencia con la reserva atómica antes de llamar al proveedor, token por variable de entorno, supuestos de D1); qué queda fuera; uso de IA (desde `docs/registro-ia.md`).
8. (Opcional) Test ArchUnit de las reglas de dependencia.

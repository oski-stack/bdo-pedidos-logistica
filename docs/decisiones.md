# Decisiones de diseño

Formato: decisión, alternativas consideradas y motivo. Este documento es la base de la sección de decisiones del README.

---

## D1. Datos para el proveedor (supuesto pendiente de confirmar)
El modelo del enunciado no incluye código postal, peso ni bultos, que el proveedor exige.
Se ha consultado al equipo. Mientras tanto, se asume:
- `Pedido.codigoPostalDestino` (5 dígitos), enviado al crear el pedido.
- `LineaPedido.pesoUnitarioKg` (`BigDecimal`, > 0).
- `pesoKg = Σ (cantidad × pesoUnitarioKg)`.
- `numBultos = Σ cantidad` (un bulto por unidad).

Esta última es una **regla de negocio provisional**: el enunciado no permite deducirla. Como el contrato del proveedor incluye `numBultos`, se interpreta que el valor varía con el pedido.
Alternativas descartadas:
- Un único bulto por pedido: dejaría `numBultos` siempre a 1 y sin sentido.
- Un bulto por línea: es igual de arbitrario y no refleja la cantidad pedida.

Limitación conocida: con muchas unidades pequeñas se obtienen demasiados bultos. El cálculo queda aislado en un único método del dominio para poder cambiarlo sin tocar el resto.
Validaciones de entrada: al menos una línea, cantidad entera > 0, peso unitario > 0.

## D2. Arquitectura
**Decisión:** monolito con hexagonal ligera, modelo de dominio rico y paquetes por funcionalidad. Los casos de uso dependen solo de puertos propios; Spring Data, JPA y el cliente HTTP quedan en adaptadores.

| Alternativa | Por qué se descarta |
|---|---|
| Microservicios | Un único contexto y dos endpoints: añadiría red y consistencia distribuida sin beneficio. |
| Hexagonal estricta (modelo de dominio ≠ modelo JPA) | Exige mappers en todas las capas, con un coste alto para este alcance. Se valoraría solo si el dominio creciera en complejidad. |

**Concesión consciente:** las entidades de dominio llevan anotaciones JPA. El dominio depende de `jakarta.persistence`, pero no de Spring ni de HTTP.

### Puertos de salida
Todos los puertos de salida viven en `pedido/application/port/out`, una ubicación única para todos. La aplicación es quien necesita esos contratos, y así el dominio solo contiene modelo y reglas.

| Puerto | Métodos principales | Adaptador |
|---|---|---|
| `PedidoRepositorio` | `guardar`, `buscarPorId`, `reservarParaConfirmacion(id): boolean` | `infrastructure/persistence` (Spring Data + `@Modifying @Query`) |
| `ClienteRepositorio` | `existe(id)` | `infrastructure/persistence` |
| `ProveedorTarifas` | `consultarTarifa(SolicitudTarifa): TarifaEnvio` | `infrastructure/proveedor` (`RestClient` + Resilience4j) |

`reservarParaConfirmacion` expresa la reserva atómica de D4 en lenguaje de negocio: el caso de uso no conoce `@Modifying` ni `JpaRepository`.

### Referencia a Cliente
`Pedido` guarda `clienteId` y no una relación JPA con `Cliente`. Es una **decisión de alcance**: el ejercicio solo necesita comprobar que el cliente existe y guardar su identificador. Además, evita cargas implícitas y acoplamiento entre ambos modelos.
Si el cliente no existe al crear el pedido → **422**. El recurso que se crea es el pedido, por lo que un 404 sería confuso.

### Invariantes de `Pedido`
- Se crea con al menos una línea y sus líneas solo se modifican a través de `Pedido`.
- `importeProductos` se calcula siempre a partir de las líneas; nunca se recibe del exterior.
- Las transiciones de estado se validan en `Pedido` (D3); una transición inválida lanza una excepción de dominio.
- `costeEnvio` e `importeTotal` solo quedan completos al confirmar: antes de `CONFIRMADO` son nulos.

## D3. Estados del pedido
```
CREADO ──reservar──▶ CONFIRMANDO ──éxito──▶ CONFIRMADO
   ▲                      │
   └──────fallo───────────┘
```
- `CONFIRMANDO` es el estado de "reserva" que impide confirmaciones concurrentes.
- Si el proveedor falla, el pedido vuelve a `CREADO` y se puede reintentar.
- `CANCELADO` queda fuera de alcance porque no hay endpoint que lo use.

## D4. Concurrencia en la confirmación
**Decisión:** reserva mediante UPDATE condicional atómico, confirmada antes de llamar al proveedor; la llamada se hace fuera de transacción.

1. **Reserva** (`PedidoRepositorio.reservarParaConfirmacion(id)`), en una transacción corta que se confirma (commit) **antes** de llamar al proveedor:
   ```sql
   UPDATE pedido
   SET estado = 'CONFIRMANDO', confirmacion_iniciada_en = :ahora, version = version + 1
   WHERE id = :id AND estado = 'CREADO'
   ```
   - 1 fila modificada: esta petición continúa.
   - 0 filas: se consulta el estado y se responde según D5.
   - La versión se incrementa manualmente porque un UPDATE masivo (JPQL/SQL) **no** pasa por el bloqueo optimista de JPA ni actualiza las entidades ya cargadas.
2. **Llamada al proveedor** sin transacción abierta.
3. **Finalización o liberación**, en una transacción nueva que **vuelve a cargar** el pedido y usa los métodos de dominio (`confirmar(tarifa)` / `revertirConfirmacion()`). Estos métodos comprueban que el estado sea `CONFIRMANDO`, y el guardado está protegido por `@Version`.

**Garantía real:** la exclusión mutua la proporciona el UPDATE condicional (la base de datos bloquea la fila durante el UPDATE). `@Version` protege la escritura de finalización frente a modificaciones intermedias.

| Alternativa | Valoración |
|---|---|
| `synchronized` / lock en memoria | ❌ No funciona con varias instancias. |
| `SELECT ... FOR UPDATE` durante la llamada | ❌ Mantiene transacción y conexión abiertas mientras dura la llamada y agota el pool bajo carga. |
| Bloqueo optimista englobando la llamada al proveedor | ❌ Ambas peticiones llamarían al proveedor antes de detectar el conflicto. |
| **Reserva con entidad + `@Version`** en una transacción corta, con commit antes de llamar | ✅ **Igualmente válida**: la petición perdedora recibe el conflicto optimista antes de llamar al proveedor y se conserva el dominio rico. Se elige el UPDATE condicional por claridad ("1 fila o 0 filas"). |

**Fuera de alcance — recuperación de reservas huérfanas:** si el proceso cae durante la confirmación, el pedido puede quedar en `CONFIRMANDO`. Se descarta la recuperación por antigüedad (por ejemplo, 30 s): un proceso pausado puede seguir vivo con la reserva caducada, provocar una segunda llamada y sobrescribir el resultado. Abordarlo requeriría un identificador de intento (`confirmacionIntentoId`) que condicione las escrituras de finalización. Eso evitaría escrituras obsoletas, pero **no** garantizaría por sí solo la ausencia de llamadas externas solapadas.

## D5. Respuestas de `POST /api/pedidos/{id}/confirmacion`
| Situación | HTTP | Estado final del pedido |
|---|---|---|
| Confirmado con éxito | 200 + pedido | `CONFIRMADO` |
| Ya estaba `CONFIRMADO` | **200 + pedido (idempotente)**, sin llamar al proveedor | `CONFIRMADO` |
| En `CONFIRMANDO` (otra petición en curso) | 409 Conflict | sin cambios |
| Pedido inexistente | 404 | — |
| Proveedor no disponible tras reintentos, o timeout | 503 + `Retry-After` | `CREADO` |
| Otro 5xx del proveedor (500, 502, 504…), sin reintento | 503 + `Retry-After` | `CREADO` |
| 401/403 u otro 4xx del proveedor (error de configuración de **nuestra** integración, no del consumidor) | 502, registrado como error | `CREADO` |
| Respuesta inválida (coste ausente, negativo, más de 2 decimales significativos, moneda ≠ EUR) o ilegible | 502 | `CREADO` |

- `Retry-After: 30` (segundos), valor fijo: sin datos reales del proveedor no hay base para calcularlo.
- Si la reserva no modifica ninguna fila y el pedido aparece en `CREADO`, otra petición lo tenía reservado y acaba de liberarlo: también es 409 y el cliente puede reintentar.
- Los errores del proveedor (503/502) llevan un `detail` fijo: el mensaje y las causas de la excepción pueden contener el cuerpo de la respuesta del proveedor.

Evolución: cabecera `Idempotency-Key`.

## D6. Resiliencia frente al proveedor
- **Timeouts por intento:** conexión 1 s, lectura 6 s (el proveedor puede tardar hasta 5 s legítimamente).
  - Con `JdkClientHttpRequestFactory` (Spring 6.2), el "timeout de lectura" es un límite de **todo el intercambio** de un intento: conexión, envío, cabeceras y cuerpo. El presupuesto real para la respuesta es 6 s menos lo que tarde la conexión.
  - Por eso la configuración exige lectura > conexión: si no, un timeout de conexión llegaría como timeout del intercambio y dejaría de reintentarse sin avisar.
- **Reintentos (Resilience4j):** **2 intentos como máximo**, solo ante errores definidos como recuperables, con backoff exponencial y jitter.
  - Recuperables: el **503 exacto** y los errores de conexión en los que la petición no llegó a enviarse (conexión rechazada, host no resoluble, timeout de conexión). El cliente del JDK entrega el host no resoluble como `ConnectException` con causa `UnresolvedAddressException`, así que queda cubierto por la comprobación de `ConnectException` (comprobado ejecutando contra un host `.invalid`).
  - **No** se reintenta tras un timeout de lectura, porque ya se ha consumido el tiempo de un intento.
  - **No** se reintentan los 4xx, los demás 5xx, las respuestas inválidas ni otros errores de E/S (por ejemplo, una conexión cortada después de enviar la petición: el proveedor pudo recibirla).
  - "2 intentos" son intentos HTTP. Ante una conexión rechazada, el cliente del JDK repite internamente la conexión TCP; no duplica peticiones, porque el proveedor no ha recibido nada.
  - Si el hilo se interrumpe durante la espera entre intentos, Resilience4j abandona sin reintentar y no restaura el flag de interrupción. En un hilo de servlet solo afecta al apagado; el log indica los intentos realmente hechos.
- **Clasificación en el código** (`ProveedorTarifasHttp`):

  | Fallo | Reintento | Resultado (D5) |
  |---|---|---|
  | 503 | Sí | Agotados los intentos: no disponible → 503 |
  | `ConnectException` (incluye el host no resoluble), `HttpConnectTimeoutException` | Sí | Agotados los intentos: no disponible → 503 |
  | `HttpTimeoutException` (timeout del intercambio) | No | No disponible → 503 |
  | Otro 5xx | No | No disponible → 503 |
  | Otro error de E/S, también si el cuerpo se corta o vence el timeout mientras llega | No | No disponible → 503 |
  | 4xx | No | Error de integración → 502 |
  | Respuesta inválida o ilegible (no es JSON válido, tipo de contenido inesperado) | No | Error de integración → 502 |

  `HttpConnectTimeoutException` **hereda** de `HttpTimeoutException`: el código comprueba primero el fallo de conexión para que un timeout de conexión no se trate como timeout de respuesta.
- **Qué es recuperable lo decide el código, no la configuración:** el `Retry` se construye a partir de `ProveedorTarifasProperties` y solo reintenta una excepción interna del adaptador. Se descarta `@Retry` con `retry-exceptions` en el yml: depende de un proxy AOP (con la misma trampa de autoinvocación que `@Transactional`) y deja la clasificación en un fichero de configuración.
- **Latencia:** los timeouts se configuran por intento; **no se establece un límite global estricto** para la operación.
  - `JdkClientHttpRequestFactory` no usa `HttpRequest.timeout()` del JDK (que solo cubre la espera hasta las cabeceras). Arranca su propio temporizador al iniciar el envío (`sendAsync`) y lo cancela al cerrar el cuerpo de la respuesta. Dentro de ese límite de 6 s por intento entran la resolución DNS, la obtención o el establecimiento de la conexión HTTP (con su propio límite de 1 s y los reintentos TCP internos del JDK), el envío, las cabeceras y el cuerpo.
  - Peor caso de la llamada al proveedor: 2 intentos × 6 s + la espera entre ellos (200 ms ± 50 %) ≈ **12,3 s**.
  - No es una garantía de la operación completa: quedan fuera el tiempo antes de iniciar el intercambio (construir la petición), el posterior (validar la respuesta) y el resto de la confirmación (las transacciones de reserva y finalización, incluida la espera por una conexión del pool de base de datos).
- **Por qué 2 intentos:** equilibrio entre capacidad de recuperación y latencia de una petición síncrona. Sin datos reales del proveedor no se puede afirmar qué porcentaje de fallos recupera un reintento.
- **Supuesto:** consultar una tarifa no tiene efectos secundarios, por lo que reintentar es seguro. El contrato no lo garantiza explícitamente.
- **Fuera de alcance:** presupuesto global de tiempo (TimeLimiter), circuit breaker y confirmación asíncrona (202 + outbox).

## D7. Importes
- `BigDecimal` en todo el código; columnas `NUMERIC(19,2)` (peso `NUMERIC(10,3)`).
- **Entrada:** `precioUnitario` con `@Positive` y `@Digits(integer = 17, fraction = 2)`. Los precios con más de 2 decimales **se rechazan, no se redondean**, para que el redondeo no oculte datos erróneos.
- Subtotal de línea = `precioUnitario × cantidad` (exacto con 2 decimales de entrada).
- `importeProductos = Σ subtotales`; `importeTotal = importeProductos + costeEnvio`.
- `costeEnvio` se deserializa directamente a `BigDecimal` (sin pasar por `double`) y se valida según D5.
- El importe lo calcula el servidor; el DTO de entrada no lo contiene.

## D8. Secretos y configuración
- El token se lee de la variable de entorno `PROVEEDOR_TOKEN` mediante `@ConfigurationProperties` validado. La app no arranca si falta. El token nunca se loguea.
- También se externalizan la URL base, los timeouts, el número de intentos y el backoff.
- En los tests se usa un token ficticio.

## D9. Persistencia
- H2 en memoria con Flyway (esquema explícito y versionado).
- Los clientes se precargan en una migración identificada como **datos de demostración**, porque no hay endpoint para crearlos.
- Los tests de concurrencia demuestran el comportamiento en H2. Que se comporte igual en PostgreSQL es una inferencia razonable (también bloquea la fila durante el UPDATE), **no verificada**. Evolución: PostgreSQL con Testcontainers.

## D10. Estrategia de tests
| Prueba | Tipo | Qué verifica |
|---|---|---|
| Cálculo del total | Unitario (sin Spring) | Subtotales, suma con envío y precisión decimal. |
| Creación del pedido | Integración | Pedido y líneas persistidos juntos; rollback si falla; rechazo de importes inválidos. |
| Timeout del proveedor | Integración + WireMock | Respuesta 503, pedido de nuevo en `CREADO`, sin reintento. |
| 503 y recuperación | Integración + WireMock | 503 → 200: 2 llamadas y pedido `CONFIRMADO`. 503 persistente: 2 llamadas y 503 al cliente. |
| Token y errores 4xx | Integración + WireMock | Cabecera `Authorization` enviada; un 401 → 502 sin reintento. |
| Dos confirmaciones simultáneas | Integración | Una única llamada al proveedor y resultado consistente. |
| Pedido ya confirmado | Integración | 200 y ninguna llamada adicional al proveedor. |

**Condiciones del test de concurrencia:**
- Base de datos **real** (H2) y repositorio real. Solo se sustituye el puerto `ProveedorTarifas` por un falso.
- **Sin `@Transactional` en el test:** cada ejecución usa sus propias transacciones, como en producción.
- El falso **retiene la primera llamada** con un `CountDownLatch`. Mientras está retenida se lanza la segunda confirmación, que debe recibir el conflicto. Después se libera la primera.
- Todas las esperas tienen límite (`await(timeout)`), para que un fallo no bloquee la suite. No se usan `sleep` arbitrarios.
- Se comprueba: exactamente 1 llamada al proveedor, una respuesta 200 y una 409, y el pedido `CONFIRMADO` con el total correcto.
- **Test adicional de carrera real:** el escenario anterior es determinista, pero la segunda petición llega cuando la reserva de la primera ya está confirmada, así que nunca compiten dos UPDATE. Por eso hay otro test: 8 peticiones salen a la vez desde una puerta común (`CountDownLatch`) y compiten por el UPDATE condicional. El ganador queda retenido en el proveedor hasta que los 7 perdedores han respondido 409: 1 llamada, 7 × 409, 1 × 200.
- Comprobación de que los tests no son vacíos: con la condición `estado = 'CREADO'` eliminada del UPDATE, ambos tests fallan.

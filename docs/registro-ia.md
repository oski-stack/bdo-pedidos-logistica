# Registro de uso de IA

He usado Claude Code como asistente. El asistente principal implementaba un paso del plan cada vez y yo revisaba los cambios y confirmaba antes de continuar. Los agentes especialistas revisaban el código, sin editarlo. Contrasté los hallazgos con el código y, cuando era posible, los reproduje antes de aplicar la corrección.

## Diseño

| Fase | Qué propuso la IA | Qué cambié y por qué |
|---|---|---|
| Diseño | Recuperar automáticamente reservas en `CONFIRMANDO` de más de 30 s | Lo descarté: un proceso pausado puede seguir vivo y provocar una doble llamada o sobrescribir el resultado. Queda documentado como fuera de alcance (D4). |
| Diseño | "`@Version` actúa como red de seguridad" junto a un UPDATE condicional | Una revisión cruzada detectó que un UPDATE masivo no pasa por el bloqueo optimista de JPA. Ahora la reserva incrementa la versión explícitamente y la finalización recarga la entidad (D4). |
| Diseño | 3 intentos y un peor caso de "~7 s" | Era incorrecto: un 503 también puede tardar. Reduje a 2 intentos y documento los timeouts por intento, sin prometer un límite global (D6). |
| Diseño | Descartar el bloqueo optimista como alternativa | Era una objeción demasiado general: reservar con `@Version` en una transacción corta, con commit antes de llamar, también es válido. Lo documento como alternativa (D4). |
| Diseño | Arquitectura hexagonal con un único puerto (`ProveedorTarifas`), con los casos de uso usando Spring Data directamente | La aplicación habría dependido de infraestructura. Añadí `PedidoRepositorio` y `ClienteRepositorio` como puertos y agrupé todos los puertos en `application/port/out` (D2). |
| Diseño | Relación JPA `Pedido → Cliente` | Guardo `clienteId`: es una decisión de alcance, porque basta con validar que el cliente existe (D2). |

## Implementación

| Paso | Qué propuso la IA | Qué cambié y por qué |
|---|---|---|
| 2 · Creación | Validar los decimales del dominio ignorando los ceros finales, "igual que `@Digits`" | Era falso: con un `BigDecimal`, `@Digits` cuenta la escala tal como llega, y `12.500` da 400. Lo detectó `java-reviewer` y lo comprobé ejecutándolo. Unifiqué el criterio estricto en web y dominio (D7). |
| 2 · Creación | DTO con `Integer cantidad` y la configuración de Jackson por defecto | `"cantidad": 1.9` se leía como `1` sin avisar y se creaba un pedido erróneo. Lo reproduje y desactivé `accept-float-as-int`: ahora da 400 (D1). |
| 2 · Creación | `@NotEmpty @Valid List<LineaPedidoRequest>` | `"lineas": [null]` pasaba la validación y acababa en un 500 por NPE. Añadí `@NotNull` al elemento de la lista. |
| 2 · Creación | Confiar en `@Digits(integer = 17)` para que el importe cupiera en la columna | Limita el precio unitario, no el subtotal ni la suma: un importe de 18 dígitos acababa en un 500 al insertar. Lo compruebo en el dominio. `numBultos()` también podía desbordar el `int`: uso `Math.addExact`. |
| 3 · Proveedor | Reintentos con `@Retry` y `retry-exceptions` en el yml | Construyo el `Retry` en código a partir de las propiedades. `@Retry` depende de un proxy AOP; una autoinvocación puede saltarse el interceptor. Así la clasificación de qué errores se reintentan queda en el código del adaptador. |
| 3 · Proveedor | Clasificar los timeouts del cliente del JDK | Pedí revisar la jerarquía: `HttpConnectTimeoutException` hereda de `HttpTimeoutException`, así que el fallo de conexión se comprueba primero. Solo se reintentan el 503 exacto y los fallos de conexión antes de enviar la petición. Hay un test que comprueba el orden. |
| 3 · Proveedor | Dejar sin definir qué pasa con un 5xx distinto de 503 | Acepté la propuesta de traducirlos a 503 para el consumidor, pero sin reintentar, porque D6 solo considera recuperable el 503. Lo documenté en D5 y hay un test con una sola llamada ante un 500. |
| 3 · Proveedor | "El timeout de lectura es el tiempo de espera de la respuesta" | Al revisar Spring Framework 6.2 vi que, con `JdkClientHttpRequestFactory`, ese timeout limita todo el intercambio, incluida la conexión. Si el límite total fuese menor o igual que el timeout de conexión, el timeout general podría producirse antes y dejar de clasificarse como fallo de conexión. La configuración exige que el timeout del intercambio sea mayor que el de conexión. |
| 3 · Proveedor | Tratar como "respuesta ilegible" (502) cualquier error al leer el cuerpo | Un corte o un timeout mientras llega el cuerpo es un fallo de comunicación, no una respuesta que incumple el contrato. Ahora da 503 sin reintento. Lo reproduje con un servidor que corta el cuerpo. |
| 3 · Proveedor | Validar el token solo con `@NotBlank` | `security-reviewer` detectó que un token con salto de línea final podía aparecer en el mensaje de una excepción. Lo reproduje. Ahora se valida su formato al arrancar, con un mensaje que no incluye el valor. |
| 3 · Proveedor | Test del token con salto de línea usando `withPropertyValues` | El test no inyectaba el salto de línea como parte literal del valor. Lo detecté porque el contexto arrancaba cuando no debía. Ahora el valor se inyecta directamente en una fuente de propiedades. |
| 3 · Proveedor | Propiedades como record con el `toString` por defecto | El `toString` de un record imprime sus campos, incluido el token. Lo sobrescribí para ocultarlo y añadí un test (D8). |
| 3 · Proveedor | Mismo criterio de decimales para la respuesta del proveedor que para nuestra entrada | Acepto `7.950` del proveedor porque es exactamente 7,95. Rechazarlo cortaría confirmaciones sin proteger ningún dato. En nuestra entrada sigo rechazando más de dos decimales para que el redondeo no oculte datos erróneos (D5, D7). |
| 4 · Confirmación | Valor de `Retry-After` sin definir | Fijé 30 s. Sin datos reales del proveedor no hay base para calcular otro valor (D5). |
| 4 · Confirmación | Leer la solicitud de tarifa en una transacción corta aparte | Basta con `findById`, que carga las líneas con `@EntityGraph`. Es menos código y da el mismo resultado. |
| 4 · Confirmación | Responder a las transiciones inválidas con la misma excepción que a los datos inválidos (400) | Creé `TransicionInvalidaException`, que da 409: es un conflicto de estado, no un error del consumidor. |
| 5 · Tests WireMock | Un test que paraba y rearrancaba WireMock para simular la conexión rechazada | Lo quité: al rearrancar cambia el puerto dinámico y podían fallar los tests siguientes. La conexión rechazada ya se prueba en el test del adaptador. |
| 5 · Tests WireMock | Test de rollback que solo comprobaba que no quedaba ningún pedido | Lo reforcé: ahora comprueba que el pedido llegó a tener id y que la fila no queda guardada. Sin comprobar que el INSERT llegó a ejecutarse, el test podía pasar sin probar el rollback. |
| 6 · Concurrencia | Solo el test con latch descrito en D10 | En ese test la reserva de la primera petición ya estaba confirmada cuando llega la segunda. Añadí una carrera real con 8 peticiones simultáneas. En mi test inicial había una carrera al contar las llamadas antes de que el ganador llegara al proveedor; la corregí. |
| 6 · Concurrencia | Dar por buenos los tests de concurrencia porque pasaban | Quité temporalmente la condición `estado = 'CREADO'` del UPDATE y comprobé que los dos tests fallaban. Después restauré el código. |
| 7 · DNS | D6 decía que un host no resoluble era un fallo de conexión reintentable, pero faltaba una prueba específica | Añadí un test para comprobar el tipo de error que presenta el cliente JDK: `ConnectException` con `UnresolvedAddressException` como causa. El adaptador lo clasifica como fallo de conexión reintentable y actualicé la documentación de D6. |
| 7 · Timeout | La explicación de D6 describía el timeout total del intercambio, pero el cálculo de latencia parecía excluir la conexión | Aclaré el cálculo para que sea coherente con el timeout de `JdkClientHttpRequestFactory`: el límite por intento cubre el intercambio completo, incluida la conexión. |
| 7 · Reloj | `Clock.systemUTC()` devolvía nanosegundos, pero H2 guardaba la fecha con precisión de microsegundos | Limité la precisión del reloj a microsegundos para alinearla con las columnas `TIMESTAMP` de H2. Añadí `ConfiguracionRelojTest` para comprobarlo. La suite terminó con 73 tests correctos. |

## Proceso

| Tema | Qué propuso la IA | Qué decidí y por qué |
|---|---|---|
| Historial de Git | Valorar Git Flow o ramas por paso | Commits directos en la rama principal, uno por paso del plan, con Conventional Commits. Con una sola persona y sin releases, Git Flow añadía complejidad sin aportar valor al ejercicio. |
| Alcance | Recortar código para que fuera más fácil de defender en un ejercicio de 3 horas | Preferí completar lo que pide el enunciado y preparar la defensa de cada decisión. |

## Agentes utilizados

Solo los que se ejecutaron de verdad. Los demás agentes configurados en `.claude/` no se usaron.

| Paso | Agente | Qué encontró | Qué decidí |
|---|---|---|---|
| 2 | `java-reviewer` | 3 defectos medios (cantidad decimal truncada, línea nula → 500, importe que no cabe en la columna) y 2 bajos (ceros finales con `@Digits`, desbordamiento de `numBultos`). También sugirió revisar la carga de líneas y los conflictos de estado | Acepté y corregí los 5 defectos, cada uno con su test. Las sugerencias las apliqué en el paso 4 (`@EntityGraph`, `TransicionInvalidaException`). |
| 3 | `resilience-reviewer` | 2 defectos medios (corte del cuerpo → 502; timeout de lectura que incluye la conexión) y 4 bajos (orden de comprobaciones sin test, validación de rangos, log de intentos tras una interrupción, reintentos TCP internos del JDK) | Corregí los medios y los bajos de código. Documenté en D6 la interrupción y los reintentos TCP. En el paso 7 añadí un test para confirmar el tratamiento del fallo DNS como error de conexión. |
| 3 | `security-reviewer` | 1 defecto medio (token con salto de línea en el mensaje de una excepción) y avisos sobre cuerpos del proveedor en excepciones encadenadas, URL en los logs de E/S y uso de HTTPS | Corregí el defecto y el log de la URL. En el paso 4 usé un detalle fijo en los errores devueltos al consumidor para no exponer mensajes o cuerpos del proveedor. El README indica que HTTPS es obligatorio en un entorno real. |
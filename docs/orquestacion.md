# Orquestación de trabajo

El proyecto incorpora una configuración para Claude Code con un agente principal que coordina el trabajo y agentes especialistas de solo lectura. La skill `/bdo-delivery` define las fases; los agentes no implementan cambios ni sustituyen la revisión del autor.

## Fases

| Fase | Responsable | Resultado esperado |
|---|---|---|
| Análisis y alcance | `pedido-architect` cuando haya decisiones de diseño | Requisitos, flujo, capas afectadas, supuestos y criterio de aceptación |
| Implementación | Asistente principal | Cambio pequeño y explicable |
| Validación | `test-validator` | Comandos ejecutados y evidencia real |
| Revisión independiente | `java-reviewer`, `resilience-reviewer` y, si aplica, `security-reviewer` | Hallazgos con evidencia, impacto y recomendación |
| Integración | Asistente principal con el autor | Decisiones sobre hallazgos, correcciones y registro de uso de IA |

## Uso

En Claude Code, desde la raíz del proyecto, se puede invocar:

```text
/bdo-delivery Implementar el siguiente paso de docs/decisiones.md
```

Para una revisión sin cambios:

```text
/bdo-delivery Revisar el estado final antes de la entrega
```

La skill coordina agentes según el riesgo; no es necesario invocar todos para cambios triviales. Los agentes se usan como revisores y sus resultados se contrastan con el código.

## Evidencia para la defensa

La presencia de esta configuración demuestra que el proyecto está preparado para una revisión por fases, pero no demuestra que los agentes se hayan ejecutado. `docs/registro-ia.md` debe anotar cada uso real: fase, agente consultado, hallazgo relevante, decisión del autor y validación ejecutada. El autor debe poder explicar qué aceptó, qué descartó y por qué.

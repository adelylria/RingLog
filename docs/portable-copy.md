# Copia portátil de RingLog

## Uso

Desde una instalación **x64** empaquetada, abrir **Archivo → Crear copia portátil…**
o la tarjeta de copia portátil en Ajustes. Elegir una carpeta o unidad con espacio
libre. Si contiene `RingLog-Portatil`, confirmar expresamente su sustitución completa.

Durante la creación, RingLog muestra un aviso permanente para no cerrar la aplicación
ni retirar el dispositivo y deshabilita las operaciones que modifican el diario.
No retirar el dispositivo hasta que aparezca «Copia lista».

Abrir la copia mediante `RingLog-Portatil.cmd`. No necesita Java instalado.
Se puede mover **la carpeta completa**, incluso a otra letra de unidad.
No abrir el JAR directamente: el CMD selecciona el modo portátil de solo lectura.

La copia permite consultar Diario, Registros, detalles, Especies, Lugares, Informes y
Revisiones, filtrar registros y guardar informes PDF/XLSX en el destino elegido.
No permite editar, importar, resolver conflictos, exportar backups nativos ni buscar
actualizaciones. Es una instantánea para consulta, no un sustituto del backup restaurable.

## Estructura

```text
RingLog-Portatil/
├── RingLog-Portatil.cmd
├── RingLog.jar
├── RingLog.ico
├── distribution.properties
├── libs/
├── runtime/
├── data/ringlog.db
├── photos/events/
├── photos/native/
├── unassigned-photos/
├── config/preferences.properties     (se crea al guardar preferencias)
├── logs/
├── portable-info.json
└── portable-manifest.json
```

Runtime: Zulu 17.42+19-CA, Java 17.0.7, Windows x64, bloqueado igual que la distribución
instalada. Launcher CMD relativo; no se añade un launcher EXE en esta fase.
El programa y sus dependencias se obtienen de la instalación que está ejecutando RingLog,
no de `target/`, del IDE ni del checkout. En desarrollo sin instalación empaquetada,
la creación informa de ese requisito.

## Garantías y límites

- El preflight exige espacio libre para **toda la copia nueva**, metadata y un margen
  de seguridad (5 %, mínimo 64 MiB). El espacio libre ya descuenta la copia existente;
  nunca se cuenta con borrarla para poder terminar el staging.
- `DataMutationCoordinator` comparte el bloqueo entre repositorios, importaciones,
  resolución de conflictos y snapshot. DB y medios se copian bajo un bloqueo exclusivo.
- La DB se obtiene con SQLite Backup API. Se validan schema exacto v4, integridad,
  claves externas, referencias gestionadas y presencia de los medios referenciados.
- Cada archivo inmutable se copia y verifica con tamaño y SHA-256 durante la creación.
  El manifest contiene rutas relativas, tamaños, tipos y hashes; `portable-info.json`
  contiene identificación técnica, recuentos y hashes de DB/manifest, sin observaciones
  ni payloads personales.
- La nueva carpeta se construye aparte. Solo después de validarla se aparta la anterior
  y se publica la nueva. Si falla la publicación se intenta restaurar la anterior.
  Si Windows impide retirar la carpeta anterior tras el éxito, se conserva como carpeta
  oculta `.RingLog-Portatil.previous-*` en vez de comprometer la copia nueva.
- La copia no modifica la DB ni las fotografías de origen. No ejecuta migraciones ni
  `DatabaseUpgradeService`; un schema distinto se rechaza.
- SQLite se abre realmente READONLY y además con `PRAGMA query_only=ON`, tanto por JDBC
  como por ORMLite. `ApplicationContext.portable()` no construye servicios de mutación.
  No es protección contra alguien que manipule los archivos con herramientas externas.
- En el arranque normal se valida estructura, DB y referencias, **sin recalcular los
  hashes de todas las fotografías**. No hay todavía botón de verificación completa.
- Preferencias y logs quedan dentro de la copia. Los informes solo escriben el fichero
  de salida elegido; no escriben estado en la DB. Debe haber permiso para escribir
  preferencias/logs en el dispositivo aunque la DB sea de solo lectura.
- Sustituir una copia reemplaza todo su contenido, incluidas sus preferencias locales.
  Guardar los informes fuera de `RingLog-Portatil` si se quieren conservar al renovarla.
- El bloqueo coordina las operaciones de esta instancia de RingLog; no bloquea herramientas
  externas. No modificar manualmente los datos ni actualizar la instalación durante la copia.

## Verificación reproducible

Con JDK 17 real:

```text
make test                         # mvn clean test; incluye RingLogTestSuite
mvn clean compile                 # JAVA_HOME y PATH deben apuntar al JDK 17
make package                      # mvn clean package; incluye toda la suite
java -cp "target/test-classes;target/classes;target/libs/*" com.adelylria.ringlog.RingLogTestSuite
java scripts/VerifyDistribution.java
java scripts/VerifyDistribution.java --startup-only
```

Surefire imprime cero tests porque el proyecto usa tests ejecutables propios. La
comprobación efectiva es `RingLogTestSuite: PASS`, ejecutada por el plugin exec de Maven.

Pruebas añadidas:

- `PortableReadOnlyTest`: INSERT, UPDATE, DELETE, DDL, rechazo de escritura ORMLite,
  ausencia de servicios de mutación, preferencias locales, rechazo de schema antiguo,
  PDF/XLSX sin cambios en el hash de la DB.
- `PortableCopyServiceTest`: staging completo, fotografías asignadas y sin asignar,
  recuentos, hashes, runtime bloqueado, launcher relativo, cambio de ubicación,
  startup sin rehash de fotos, falta de espacio conservando la copia anterior,
  rollback de publicación y exclusión de mutaciones durante el snapshot.
- Integración con el workbook real v5.2 del migrador: 289 eventos y 9 fotografías
  físicas sin asignar, consulta de detalles, informes PDF/XLSX y movimiento de carpeta.
  El workbook original solo se lee; los datos importados se almacenan en temporales.
- `PortableStartupProbe`: prueba adicional pospackage en un proceso con el runtime
  copiado y las clases del JAR empaquetado, arranque real de `RingLog.main`, navegación,
  preferencias locales y ausencia de updater/edición. Se ejecuta antes y después de mover
  la carpeta, con APPDATA/LOCALAPPDATA señuelo que deben permanecer inexistentes.

Para ejecutar la integración adicional, preparar primero la distribución x64 con
`PrepareWindowsDistribution` (ver `windows-distribution.md`), y ejecutar:

```text
java -Dringlog.portable.installed.root=<instalacion-x64> -cp "target/test-classes;target/classes;target/libs/*" com.adelylria.ringlog.portable.PortableCopyServiceTest
```

Opcionalmente `-Dringlog.portable.validation.output=<carpeta-nueva>` conserva una copia
validada y sus informes para la VM. La carpeta debe no existir y su padre debe existir.
Sin esa opción, las pruebas eliminan todos sus temporales después de cerrar los procesos.
Los artefactos generados y el dataset nunca se versionan.

## Validación física

El 8 de septiembre de 2026 el usuario confirmó que la copia funciona correctamente en
una VM Windows 8.1 x64. Quedan como comprobaciones de hardware: pendrive real y cambio
de letra, rendimiento con muchos medios, desconexión del dispositivo y sustitución de
una copia que esté abierta. No se añade todavía launcher EXE, versión nueva ni
publicación de releases.

## Resultado del cierre — 5 de septiembre de 2026

| Comprobación | Resultado |
| --- | --- |
| JDK real Zulu 17.0.7 x64, `mvn clean test` | PASS |
| `mvn clean compile` | PASS |
| `mvn clean package` | PASS |
| `RingLogTestSuite` independiente: 53 ejecutables de test | PASS |
| `VerifyDistribution` y arranque aislado del JAR normal | PASS |
| Copia del v5.2 real: 4 especies, 271 aves, 4 lugares, 289 eventos | PASS |
| 9 fotografías físicas sin asignar; foto asignada cubierta con fixture adicional | PASS |
| SQLite/ORMLite rechazan escrituras y DDL; contexto sin servicios de mutación | PASS |
| PDF de 289 registros y XLSX, sin cambiar SHA-256 de la DB | PASS |
| Arranque real, navegación y detalle desde el JAR y runtime copiados | PASS |
| Mover la carpeta y repetir arranque/navegación con el runtime copiado | PASS |
| Preferencias/logs portátiles; APPDATA/LOCALAPPDATA señuelo sin escrituras | PASS |
| Limpieza de temporales y cierre de procesos; solo se retiene la copia solicitada | PASS |
| Compilación del instalador x64 con Inno Setup 6.7.3 | PASS |
| Prueba del portable en VM Windows 8.1 x64, confirmada por el usuario | PASS |
| Prueba en pendrive y portátil físicos | PENDIENTE |

Durante el cierre se corrigieron dos detalles: el aviso de creación ahora permanece
en la ventana aunque cambie el texto de progreso o se navegue a otra sección; el
instalador incluye `distribution.properties`, necesario para identificar la instalación
al crear la copia. Se añadió una comprobación de empaquetado que falló antes de esta
última corrección y pasó después.

Artefactos locales de validación (ignorados por Git):

- `target/portable-validation/RingLog-Portatil/`: copia completa, 362 archivos,
  178.207.961 bytes tras los arranques de prueba, con preferencias/logs locales.
- `target/portable-validation/reports/legacy-289.pdf`: 558.171 bytes.
- `target/portable-validation/reports/legacy-289.xlsx`: 48.874 bytes.
- `target/windows-installer/output/x64/RingLog-Setup-x64.exe`: 69.439.318 bytes,
  versión 0.0.1; SHA-256
  `6d5f725702d74d222d722e8b1451f501a6a448859796e14d95222d0f9cf97cac`.

**Copiar la carpeta portátil completa fuera de `target/` antes de ejecutar otro build
limpio**, ya que Maven limpia ese directorio. El instalador x64 se recompiló para
validar la inclusión de la metadata; no se ejecutó sobre la instalación del usuario.
No se modificaron la DB real, las fotografías reales, el schema, la versión del
proyecto ni el canal de actualizaciones. No hay publicaciones ni pushes.

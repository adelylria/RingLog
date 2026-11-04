# Benchmarks de RingLog

Esta herramienta mide datos de prueba; no abre el diario del usuario. Cada grupo y tamaño se
ejecuta en una JVM independiente. Crea una base SQLite real con el schema actual, catálogos,
historiales, notas y 24 fotografías JPEG de 2400×1800. La preparación queda fuera de los tiempos.

## Ejecutar en el portátil Windows 8.1

Descomprime `RingLog-Benchmarks.zip` en una carpeta con permiso de escritura y abre
`Ejecutar-benchmark.cmd`. Utiliza el Java 17 incluido en la instalación habitual de RingLog;
no necesita Maven, JDK ni conexión a Internet. La ejecución inicial usa 1.000 registros.
Incluye una ventana de prueba que navega automáticamente y se cierra al terminar; conviene
dejarla en primer plano y no interactuar con ella durante la medición.
Los resultados quedan en `results/<fecha>/report.md`, `results.csv` y `results.json`.
El JSON contiene muestras individuales y características del entorno, sin nombres de usuario
ni nombres del equipo. No se envía ningún archivo automáticamente.

Si RingLog está instalado en otra carpeta, ejecuta PowerShell desde la carpeta descomprimida:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\RunBenchmarks.ps1 `
  -SkipBuild -IncludeDesktop -Rows 1000 -JavaPath 'C:\ruta\RingLog\runtime\bin\java.exe'
```

Conviene cerrar otras aplicaciones pesadas, conectar el cargador y repetir dos o tres veces
en las mismas condiciones. No cambies el plan de energía entre comparaciones. Conserva los
informes para compararlos; no son un diagnóstico automático del sistema operativo.

## Preparar y ejecutar desde el repositorio

Se necesitan los mismos JDK 17 y Maven del proyecto; las dependencias deben estar disponibles
localmente. El script no descarga runtimes ni cambia la versión ni publica actualizaciones.

```powershell
.\scripts\RunBenchmarks.ps1 -Rows 1000,10000 -CreatePortableKit
.\scripts\RunBenchmarks.ps1 -Profile full -Rows 1000,10000,50000
.\scripts\RunBenchmarks.ps1 -Groups db -Rows 10000 -HistoryLength 100
.\scripts\RunBenchmarks.ps1 -SkipBuild -Groups ui -Rows 10000 -Samples 7 -Warmups 3
.\scripts\RunBenchmarks.ps1 -Groups portable -Rows 1000
.\scripts\RunBenchmarks.ps1 -Groups desktop -Rows 1000 -Samples 3
.\scripts\CompareBenchmarks.ps1 -Before target\benchmarks\antes\results.json `
  -After target\benchmarks\despues\results.json -OutputFile target\benchmarks\comparacion.csv
```

`-HeapMiB` fija el máximo de heap (512 MiB por defecto); la prueba también puede ejecutarse
con 256 MiB para representar un portátil con poca memoria. Esto no simula su CPU, disco, GPU,
controladores, antivirus ni Windows 8.1. `-ReportLimit` limita los informes (250 registros por
defecto). Los grupos pesados usan menos repeticiones para no producir archivos innecesarios.
Cada proceso tiene un límite de 20 minutos para evitar bloqueos indefinidos. Puede ampliarse
con `-TimeoutSeconds` (hasta 14.400); si vence, el informe marca el proceso como incompleto.

## Cobertura e interpretación

- Base de datos: apertura, validación, inicialización, diario completo, recientes, historial,
  detalle, búsqueda de anilla, estadísticas, catálogos, informes y escrituras representativas.
- Interfaz: construcción, carga asentada y pintura de las pantallas principales; filtros,
  ordenación, calendario y paginación. Se separa el tiempo de la acción en el hilo de interfaz
  de la espera total del resultado. La pintura se realiza en memoria, no en una pantalla física.
- Archivos: informes Excel/PDF, exportación y restauración nativas, backup v3, snapshot SQLite,
  decodificación y gestión física de fotos. Se comprueban formatos, conteos y referencias.

La mediana representa una ejecución típica; p95 muestra la cola lenta, aunque con pocas
muestras es solo orientativo. Los JSON guardan los valores originales, GC y memoria observada.
No se fuerza GC ni se vacía la caché del sistema operativo. Por tanto, “frío” significa primera
ejecución de ese caso en su JVM, no un disco físicamente frío. Las pruebas de carga del proceso
incluyen preparación y no deben confundirse con el arranque de la aplicación.

El grupo opcional `portable` mide la creación y validación completas de una copia portátil
con su runtime, aplicación, datos sintéticos y fotografías. Es más pesado y solo admite el JRE
x64 fijado por esta función de RingLog; un JDK o un runtime x86 se registra como `SKIPPED`.
El destino medido es el disco local de resultados, no un USB físico.

El grupo opcional `desktop` abre una ventana identificada como benchmark, con el diario
sintético, y mide construcción, primera pintura y navegación. Es la comprobación que conviene
ejecutar también en el portátil para incluir su sistema gráfico. Se cierra al terminar; no usa
el diario instalado ni realiza búsquedas de actualizaciones. Los tiempos no incluyen que un
usuario mueva o pulse físicamente el ratón.
También abre el calendario mediante su botón real y cambia de mes con el popup visible,
separando la acción, su pintado y la sincronización con el sistema gráfico.

No se mide la red pública de mapas/actualizaciones, el instalador ni la latencia física de entrada.
Tampoco se pretende que unos pocos casos cubran cada rama de código; la suite
funcional sigue siendo la que verifica la corrección.

Los cambios de tiempo solo deben atribuirse al código al comparar el mismo equipo, JVM,
heap, tamaños, historial, fotos y condiciones. `CompareBenchmarks.ps1` avisa si detecta cambios
en las características básicas del entorno. No hay umbrales absolutos que hagan fallar CI en
máquinas lentas; un error de integridad o una operación fallida sí devuelve un código de error.

# Optimización de exportaciones completas y PDF — 4 de noviembre de 2026

## Cambios aplicados

Los exports nativos y los backups v3 escriben las celdas con una ventana de 128 filas.
La tabla de cadenas compartidas deduplica los textos. Esto evita conservar todas las
celdas del archivo en memoria y repetir textos largos en el XML. Se mantienen las hojas,
cabeceras, tipos, referencias, versiones de formato y validación completa del resultado.
Los datos de la captura y las cadenas únicas siguen formando parte de la memoria del proceso.

La salida usa buffers de 64 KiB. El backup calcula las anchuras de las columnas con un
recorrido acotado, conserva los textos y evita el autoajuste gráfico de todas las celdas.
Las fotos se leen una sola vez a su snapshot privado y se codifican por fragmentos, evitando
copias completas sucesivas. La preparación termina antes de validar: esa segunda etapa recibe
solo rutas y conteos, y deja de retener el snapshot de filas y fotos. Los lectores abren el
XLSX directamente desde archivo en modo lectura para reducir copias en memoria.
El lector nativo detecta el formato en el libro que ya tiene
abierto, conservando todos sus controles y evitando una apertura y parseo completos duplicados.

La tabla compartida del backup protege los caracteres de control y los literales
`_xNNNN_` mediante escapes SpreadsheetML. Solo ese componente del ZIP usa compresión Huffman
sin coincidencias LZ77: así los textos legítimos muy repetidos conservan su compatibilidad
con los límites de expansión del lector. El resto del XLSX mantiene su compresión habitual.
La tabla de cadenas puede ocupar más bytes comprimidos; las comprobaciones de seguridad
del lector conservan sus límites.

El PDF reutiliza métricas por fuente y evita volver a calcular prefijos completos al
ajustar líneas. Una caché acotada por fuente y documento reutiliza los comandos de texto
generados por PDFBox para cadenas repetidas. Conserva la composición GSUB de la biblioteca,
las fuentes, sus subconjuntos, tamaños, colores y posiciones. La caché admite hasta 2.048
textos de 1.024 caracteres por fuente; el resto sigue la ruta habitual de PDFBox.

## Diagnóstico y comprobaciones

Un perfil JFR separado de las comparaciones identificó el coste en construcción/serialización
XML de los libros y en la composición GSUB repetida del PDF. Las consultas del snapshot
representaban una parte mucho menor del tiempo. El perfil y su resumen están en
`target/benchmarks/export-pdf-profile-before`.

Se comparó el mismo PDF de 250 registros con fecha y datos fijados, antes y después:
**503 páginas, texto completo idéntico y SHA-256 idéntico del raster de cada página a 96 dpi**.
La comprobación incluye las páginas de continuación. Los artefactos están en
`target/benchmarks/pdf-equivalence`.

Las regresiones cubren filas ya expulsadas de la ventana de escritura, restauración real,
hashes y fragmentos de fotos, métodos de captura archivados, campos vacíos/null, Unicode,
caracteres de control, textos desbordados y literales de escape. Incluyen 515 eventos variados,
una celda con 30.000 NUL y 513 eventos con textos de 20.000 caracteres repetidos. También
comprueban que se limpian los temporales y que un fallo no sustituye el destino existente.

Los tests PDF contrastan anchuras y saltos de línea, operadores, texto y píxeles después de
guardar y reabrir, con ligaduras, escapes, tamaños y colores distintos. Otra prueba verifica
que la validación recorre contenido corrupto en la última página tras una portada válida;
se comprobó también contra la implementación anterior de la validación.

## Condiciones de las mediciones

Windows 11 amd64, Java Azul 17.0.7 de 64 bits, G1, 12 procesadores lógicos y heap máximo
de 512 MiB. Cada tamaño se ejecuta en una JVM independiente con una base SQLite y fotos
sintéticas. El perfil general configura dos calentamientos y tres muestras; estos casos
pesados de archivos usan cero calentamientos y tres muestras, tal como registra cada JSON.
La preparación de datos queda fuera del tiempo. El p95 de tres muestras es orientativo.

Las exportaciones completas incluyen su validación pública. El informe PDF contiene
250 registros y su validación completa. Las restauraciones adicionales comprueban los
archivos generados. Los tiempos corresponden al equipo local y a estas cargas concretas.

## Resultados finales

La suite completa terminó con `RingLogTestSuite: PASS` y `BUILD SUCCESS`: 69 clases de
pruebas. Los 30 resultados de archivos con 512 MiB y los 15 de 1.000 registros con 256 MiB
pasaron, incluyendo generación, análisis, restauración, snapshots y operaciones de fotos.

| Operación | Registros en el archivo | Mediana anterior | Mediana final | Reducción observada |
|---|---:|---:|---:|---:|
| PDF de 250 registros | 1.000 | 4,37 s | 1,45 s | 66,8 % |
| Export nativo completo | 1.000 | 2,00 s | 1,49 s | 25,3 % |
| Backup v3 completo | 1.000 | 1,73 s | 1,00 s | 42,2 % |
| PDF de 250 registros | 10.000 | 4,85 s | 1,68 s | 65,4 % |
| Export nativo completo | 10.000 | 9,87 s | 5,71 s | 42,2 % |
| Backup v3 completo | 10.000 | 10,74 s | 3,53 s | 67,1 % |

La comprobación automatizada de entorno y carga da `Comparable=True` para estos pares.
Son medianas de tres muestras por caso, con preparación y comprobaciones adicionales del
benchmark fuera del tiempo medido. Las operaciones públicas incluyen su propia validación.
Los porcentajes se refieren a estas cargas y al código final, con cadenas compartidas y
tratamiento completo de los casos límite.

En esta carga de 10.000 registros, el backup XLSX pasó de 3,33 a 3,98 MB de media
(aproximadamente un 19,4 % más), por la estrategia de compresión de su tabla de cadenas.
Esta elección conserva el rendimiento y permite validar textos muy repetidos sin modificar
los límites de seguridad. Las fotos y los textos restaurados conservan sus bytes y valores.

Los informes originales, muestras, p95, GC, memoria observada y huellas de fuentes/clases están
en `target/benchmarks/export-pdf-before` y `target/benchmarks/export-pdf-complete`.
La comparación está en `target/benchmarks/export-pdf-comparison.csv`; el log de la suite final,
en `target/benchmarks/export-pdf-complete-functional.log`. La comprobación de 1.000 registros
con 256 MiB está en `target/benchmarks/export-pdf-256m-1000`. Esa última usa una muestra
sin calentamiento y acredita corrección con esa memoria, no una comparación de rendimiento.

## Límite de memoria observado

Con 10.000 registros y `-Xmx256m`, el export nativo agota el heap tanto en la versión anterior
como en la optimizada: antes durante la escritura y ahora al validar con el DOM de XSSF.
La liberación anticipada del snapshot reduce el solapamiento, pero ese lector completo
sigue siendo el límite para esta carga restringida. Las comparaciones de tiempos usan 512 MiB.
El diagnóstico de la versión anterior está en `target/benchmarks/export-pdf-before-256m.log`,
y el de la versión optimizada en `target/benchmarks/native-export-producer-release-256m.log`.
Un lector basado en eventos sería el siguiente cambio estructural para reducir ese límite;
los controles del lector actual se mantienen completos.

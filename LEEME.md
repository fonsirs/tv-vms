# TV VMS — visor de cámaras Dahua / Hikvision / ONVIF para Android TV

Cuadrículas de 4, 8 y 16 cámaras, pantalla completa, paginación y rotación automática. Se maneja solo con el mando.

## Cómo conseguir el APK (una sola vez)

1. Entra en https://github.com/new con tu cuenta. Nombre: `tv-vms`. Elige **Public** (no hay claves dentro del código; así el enlace de descarga es directo). Pulsa **Create repository**.
2. En la página del repositorio vacío pulsa **uploading an existing file**.
3. Descomprime el ZIP y arrastra **todo el contenido** de la carpeta `tv-vms` (no la carpeta en sí) a la ventana. En Mac, pulsa Cmd+Shift+. en Finder para ver la carpeta oculta `.github`.
4. Abajo pulsa **Commit changes**.
5. Comprueba en la página principal del repositorio que existe la carpeta `.github`. Si no aparece: **Add file → Create new file**, escribe como nombre `.github/workflows/build.yml` (las barras crean las carpetas) y pega el contenido del archivo `build.yml` del ZIP. Pulsa **Commit changes**.
6. Pestaña **Actions**: verás «Compilar APK» en marcha (3–6 minutos). Espera al tick verde.
   - Si sale una X roja: abre el trabajo, copia el texto de la parte en rojo y pásamelo. Lo corrijo y te devuelvo los archivos.
7. Descarga: en la columna derecha del repositorio, **Releases** → la última → archivo `TV-VMS.apk`.

Enlace directo para la TV (cambia TUUSUARIO): `https://github.com/TUUSUARIO/tv-vms/releases/latest/download/TV-VMS.apk`

## Instalarlo en la TV

1. Instala **Downloader** (de AFTVnews) desde Google Play en la TV.
2. Ajustes de la TV → Seguridad → **Fuentes desconocidas** → activa Downloader.
3. En Downloader escribe el enlace directo de arriba → Instalar.

## Uso con el mando

| Acción | Botón |
|---|---|
| Moverse entre cámaras | flechas |
| Ver una cámara a pantalla completa (flujo principal, con audio) | OK |
| En pantalla completa: cámara anterior / siguiente | ◀ ▶ (o canal −/+) |
| Volver a la cuadrícula | Atrás u OK |
| Mostrar la barra superior | ↑ desde la fila de arriba, o botón Menú |

Barra superior: **4 / 8 / 16** (tamaño de cuadrícula), **◀ ▶** (página), **Auto** (cambia de página cada 15 s), **Flujo SD/HD** (SD = flujo secundario, ligero; HD = principal), **Cámaras** (añadir, editar, mover, eliminar).

## Añadir cámaras

- **Añadir NVR / DVR**: pones IP, usuario, contraseña y rango de canales (1 a 16) y crea todas las cámaras de golpe. Es lo más rápido.
- **Añadir cámara**: Dahua o Hikvision (IP + canal), **ONVIF** (la app consulta la cámara y saca sola las URL principal y secundaria) o **URL RTSP** libre.
- URL que usa la app: Dahua `rtsp://…/cam/realmonitor?channel=N&subtype=0|1`, Hikvision `rtsp://…/Streaming/Channels/N01|N02`.

## Límites a tener en cuenta

- La TV decodifica en hardware y cada modelo tiene un máximo de vídeos simultáneos. Por eso la cuadrícula usa el flujo secundario por defecto. Si una caja se queda corta con 16, usa 8 o mejor un reproductor más potente (Shield, etc.).
- Las contraseñas se guardan en el almacenamiento privado de la app, sin cifrar. Usa un usuario de solo visualización del NVR para la TV.
- Es una primera versión: sin grabación, sin reproducción de histórico, sin PTZ.

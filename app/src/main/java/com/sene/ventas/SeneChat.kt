package com.sene.ventas

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun Activity.showSeneChat() {
    val density = resources.displayMetrics.density
    fun d(n: Int) = (n * density).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(14), d(14), d(14), d(12))
        setBackgroundColor(0xFFF3F6FC.toInt())
    }
    root.addView(TextView(this).apply {
        text = "SENE IA · CHAT"; textSize = 21f; letterSpacing = .06f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setTextColor(0xFF17264A.toInt()); gravity = Gravity.CENTER
        setPadding(0, d(4), 0, d(5))
    })
    root.addView(TextView(this).apply {
        text = "Pregúntame por ventas, fechas, movimientos, caja e inventario. Si mencionas Internet, web, nube o red, buscaré información y mostraré un resumen con enlaces para abrir las fuentes. SENE no guarda las consultas ni los resultados web. La consulta se envía a DuckDuckGo, que aplica sus propias políticas de privacidad. Tus datos de ventas e inventario no se envían al buscador."
        textSize = 13f; setTextColor(0xFF5C6880.toInt()); setPadding(d(5), d(5), d(5), d(10))
    })
    val transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = true; addView(transcript) }
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    fun bubble(message: String, user: Boolean) {
        val item = TextView(this).apply {
            text = message; textSize = 15f
            setTextColor(if (user) 0xFFFFFFFF.toInt() else 0xFF20304E.toInt())
            setPadding(d(13), d(11), d(13), d(11))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (user) intArrayOf(0xFF35599F.toInt(), 0xFF263E78.toInt()) else intArrayOf(0xFFFFFFFF.toInt(), 0xFFEAF0FA.toInt())
            ).apply { cornerRadius = d(16).toFloat() }
            setLineSpacing(d(2).toFloat(), 1.04f)
            if (!user) {
                Linkify.addLinks(this, Linkify.WEB_URLS)
                setLinkTextColor(0xFF1D5FBF.toInt())
                movementMethod = LinkMovementMethod.getInstance()
            }
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (user) Gravity.RIGHT else Gravity.LEFT
            setPadding(d(2), d(4), d(2), d(4))
            addView(item, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .78f).toInt(), -2))
        }
        transcript.addView(line)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    bubble("¡Hola! Soy SENE IA. Pregúntame, por ejemplo: «¿Cuánto vendí hoy?», «¿Qué movimientos hubo ayer?», «¿Cuánto queda en caja?» o «¿Cuándo se actualizó el inventario?».", false)
    val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8), 0, 0) }
    val question = EditText(this).apply {
        hint = "Escribe tu pregunta…"; textSize = 15f; maxLines = 3
        setTextColor(0xFF17213D.toInt()); setHintTextColor(0xFF929BB0.toInt())
        setPadding(d(12), d(10), d(12), d(10))
        background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = d(13).toFloat(); setStroke(d(1), 0xFFDCE2EF.toInt()) }
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
    }
    val send = Button(this).apply {
        text = "Enviar"; isAllCaps = false; textSize = 14f
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF4267B8.toInt(), 0xFF243D78.toInt())).apply { cornerRadius = d(13).toFloat() }
        setPadding(d(10), d(8), d(10), d(8))
    }
    composer.addView(question, LinearLayout.LayoutParams(0, d(50), 1f))
    composer.addView(send, LinearLayout.LayoutParams(d(86), d(50)).apply { leftMargin = d(7) })
    root.addView(composer)
    val dialog = AlertDialog.Builder(this).setView(root).setNegativeButton("Cerrar", null).create()
    fun submit() {
        val raw = question.text.toString().trim()
        if (raw.isBlank()) return
        question.setText("")
        bubble(raw, true)
        val localAnswer = SeneLocalAssistant.answer(this, raw)
        val asksInternet = SeneWebLookup.shouldSearch(raw)
        fun searchInternet() {
            val waiting = TextView(this).apply {
                text = "Investigando en Internet…"
                textSize = 13f; setTextColor(0xFF60718F.toInt())
                setPadding(d(12), d(7), d(12), d(7))
            }
            transcript.addView(waiting)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            Thread {
                val result = SeneWebLookup.search(raw)
                runOnUiThread {
                    transcript.removeView(waiting)
                    bubble(result ?: "No encontré una respuesta gratuita para esa consulta. Puedes intentar formularla de otra manera.", false)
                }
            }.start()
        }
        if (asksInternet) {
            searchInternet()
        } else if (localAnswer.startsWith("Puedo ayudarte con ventas, movimientos, pagos a proveedores, caja e inventario.")) {
            bubble("No encontré esa respuesta en mi conocimiento local. ¿Quieres que la busque en Internet? No guardaré la consulta ni los resultados.", false)
            val choices = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(d(4), d(3), d(4), d(8))
            }
            val yes = Button(this).apply {
                text = "Sí, buscar"; isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    choices.findViewWithTag<Button>("no_web")?.isEnabled = false
                    bubble("Sí, busca en Internet.", true)
                    searchInternet()
                }
            }
            val no = Button(this).apply {
                tag = "no_web"
                text = "No, gracias"; isAllCaps = false
                setOnClickListener {
                    yes.isEnabled = false
                    isEnabled = false
                    bubble("De acuerdo. No haré ninguna búsqueda. Puedes explicarme qué respuesta esperabas para mejorar mi conocimiento local.", false)
                }
            }
            choices.addView(yes)
            choices.addView(no)
            transcript.addView(choices)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        } else {
            bubble(localAnswer, false)
        }
    }
    send.setOnClickListener { submit() }
    question.setOnEditorActionListener { _, action, event ->
        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
            (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && !event.isShiftPressed)) { submit(); true } else false
    }
    dialog.setOnShowListener {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), (resources.displayMetrics.heightPixels * .82f).toInt())
    }
    dialog.show()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), (resources.displayMetrics.heightPixels * .82f).toInt())
}

private object SeneLocalAssistant {
    private val locale = Locale("es", "MX")
    private fun fmt() = SimpleDateFormat("dd/MM/yyyy", locale)
    private fun bounds(date: Date): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start = c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, 1); return start to c.timeInMillis
    }
    private fun requestedDate(q: String): Date {
        val c = Calendar.getInstance()
        if ("anteayer" in q || "antier" in q) c.add(Calendar.DAY_OF_MONTH, -2)
        else if ("ayer" in q) c.add(Calendar.DAY_OF_MONTH, -1)
        else {
            val m = Regex("""(?<!\d)(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})(?!\d)""").find(q)
            if (m != null) {
                val y0 = m.groupValues[3].toInt(); val y = if (y0 < 100) 2000 + y0 else y0
                try { return fmt().parse("%02d/%02d/%04d".format(locale, m.groupValues[1].toInt(), m.groupValues[2].toInt(), y)) ?: Date() } catch (_: Exception) { }
            }
        }
        return c.time
    }
    fun answer(a: Activity, raw: String): String {
        val q = raw.lowercase(locale)
        val shiftExitQuery = listOf("a que hora salgo", "a qué hora salgo", "hora de salida", "cuando termina mi jornada", "cuándo termina mi jornada").any { it in q }
        if (shiftExitQuery) return SeneApprovedKnowledge.shiftExitAnswer(a)
            ?: "No hay un turno activo para consultar un horario de salida."
        SeneApprovedKnowledge.answer(a, raw)?.let { return it }

        val today = fmt().format(Date())
        val date = requestedDate(q)
        val (from, until) = bounds(date)
        val label = fmt().format(date)
        val sales = Store.sales(a); val moves = Store.moves(a); val shift = Store.shift(a); val products = Store.products(a)
        val salesQuery = listOf("venta", "ventas", "vendí", "vendi", "vendido", "vendió", "vendio", "vendimos", "se vendió", "se vendio", "recaud").any { it in q }
        val moveQuery = listOf("movimiento", "movimientos", "proveedor", "pago", "gasto", "abasto").any { it in q }
        val stockQuery = listOf("stock", "inventario", "existencia", "agot", "producto").any { it in q }
        val cashQuery = listOf("caja", "efectivo", "dinero").any { it in q }
        // Las preguntas de procedimiento deben resolverse antes de consultar el stock.
        // Evita interpretar "¿por qué no puedo agregar inventario?" como búsqueda de existencias bajas.
        val inventoryHelp = listOf("agregar inventario", "añadir inventario", "no puedo agregar", "no me deja agregar",
            "como agrego", "cómo agrego", "como puedo agregar", "cómo puedo agregar", "registrar producto",
            "dar de alta", "llego proveedor", "llegó proveedor").any { it in q }
        if (inventoryHelp) return listOf(
            "Para recibir mercancía: escanea el código en VENTAS. Si es un producto nuevo o está agotado, la app te ofrecerá AGREGAR INVENTARIO. Captura la cantidad recibida y revisa los datos en las dos confirmaciones antes de guardar.",
            "Si llegó un producto que no aparece en el catálogo, escanea su código y elige AGREGAR INVENTARIO. Escribe descripción, precio de venta y cantidad recibida. Si ya existe pero está en cero, se conserva su descripción y precio; solo agregas las piezas que llegaron.",
            "El permiso del trabajador es para dar de alta productos nuevos que llegaron del proveedor o reponer productos agotados. No es para cambiar precios ni modificar existencias de productos que todavía tienen stock. Confirma cuidadosamente: el ingreso no se puede deshacer."
        ).random()

        // Guía de operación: se entrega una sola explicación breve por pregunta.
        val workflowHelp: String? = when {
            listOf("primer inventario", "crear inventario", "hacer inventario inicial", "inventario inicial", "dar de alta productos").any { it in q } ->
                listOf(
                    "Para crear el inventario inicial, entra a Soy Patrón y pulsa Hacer primer inventario. Escanea cada producto o captura su código, descripción, cantidad inicial y precio de venta. Pulsa AGREGAR PRODUCTO y, al terminar, GUARDAR INVENTARIO.",
                    "El primer inventario se captura desde Soy Patrón. Agrega los productos uno por uno con código, nombre, existencia y precio. Revisa la lista antes de guardar el archivo en INVENTARIO SENE.",
                    "Si todavía no existe un catálogo, el patrón debe crear el primer inventario. La app te pedirá seleccionar o crear la carpeta INVENTARIO SENE para guardar el respaldo con fecha y hora."
                ).random()
            listOf("guardar inventario", "guardar txt", "respaldo de inventario", "respaldar inventario", "exportar inventario", "copia de inventario", "carpeta segura").any { it in q } ->
                listOf(
                    "Para guardar una copia del stock, entra a Soy Patrón y pulsa GUARDAR TXT DEL STOCK. El archivo se guarda en INVENTARIO SENE. Si aún no configuraste la carpeta, selecciónala cuando la app lo solicite.",
                    "El respaldo del inventario se guarda como TXT con fecha y hora. Desde Soy Patrón usa GUARDAR TXT DEL STOCK; conserva esa carpeta para que SENE pueda guardar ahí los siguientes respaldos.",
                    "El respaldo TXT sirve para recuperar el catálogo y sus existencias. No lo confundas con el reporte de ventas del turno: son archivos distintos."
                ).random()
            listOf("cada cuanto", "cada cuánto", "dos horas", "2 horas", "reporte pendiente", "no me deja vender", "no permite vender", "bloqueo de venta").any { it in q } ->
                listOf(
                    "SENE solicita compartir un reporte cada 2 horas. Cuando el contador llega a cero, puede bloquear temporalmente el botón de venta hasta que se envíe el reporte pendiente por WhatsApp.",
                    "El aviso SIGUIENTE REPORTE EN cuenta el tiempo restante. Si aparece REPORTE PENDIENTE: ENVÍA PARA CONTINUAR, genera y comparte el reporte solicitado; después podrás continuar vendiendo.",
                    "El reporte periódico es un respaldo de los movimientos del turno. No es un cobro adicional: sirve para enviar y conservar la información registrada."
                ).random()
            listOf("cambiar precio", "modificar precio", "actualizar precio", "precio incorrecto", "subir precio", "bajar precio").any { it in q } ->
                listOf(
                    "Para cambiar un precio, entra a Soy Patrón, pulsa AGREGAR O MODIFICAR PRODUCTO y escanea el código. Selecciona MODIFICAR PRECIO, captura el nuevo importe y guarda. El trabajador no tiene permiso para cambiar precios.",
                    "Los precios se administran desde Soy Patrón con el PIN. Localiza el producto con el escáner y elige MODIFICAR PRECIO. Revisa el importe antes de confirmar.",
                    "Si el producto ya existe, el precio se conserva durante una recepción normal del trabajador. Para modificarlo debes entrar como patrón."
                ).random()
            listOf("quitar existencia", "quitar stock", "retirar producto", "ajustar inventario", "ajuste de inventario", "corregir existencia").any { it in q } ->
                listOf(
                    "Los ajustes manuales de existencia se hacen desde Soy Patrón. Entra a AGREGAR O MODIFICAR PRODUCTO, escanea el artículo y elige AGREGAR MÁS o QUITAR PRODUCTO. El sistema no permite retirar más piezas de las que existen.",
                    "Para corregir una cantidad, el patrón debe localizar el producto con el escáner y elegir el ajuste correspondiente. La app guarda el cambio y registra un movimiento de ajuste.",
                    "El trabajador solo puede recibir productos nuevos o reponer productos agotados conforme al permiso de recepción. Los ajustes manuales del catálogo corresponden al patrón."
                ).random()
            listOf("no aparece el producto", "producto no registrado", "producto no encontrado", "sin registrar", "no está registrado", "no esta registrado", "producto agotado", "está agotado", "esta agotado").any { it in q } ->
                listOf(
                    "Si al escanear aparece NO REGISTRADO o el producto está agotado, el trabajador puede usar AGREGAR INVENTARIO para registrar la mercancía recibida del proveedor. Captura los datos solicitados y confirma antes de guardar.",
                    "Si es un producto nuevo, captura su descripción, precio y cantidad recibida. Si ya existe pero está en cero, se conserva el nombre y precio registrados y solo se agrega la cantidad recibida.",
                    "No uses esta opción para cambiar precios ni para aumentar productos que todavía tienen existencia. Está destinada a mercancía nueva o reposición recibida del proveedor."
                ).random()
            listOf("no escanea", "no lee el codigo", "no lee el código", "lector no funciona", "camara no abre", "cámara no abre", "permiso de camara", "permiso de cámara").any { it in q } ->
                listOf(
                    "En VENTAS pulsa ESCANEAR PRODUCTO y permite el acceso a la cámara si Android lo solicita. Busca buena iluminación y coloca el código dentro del recuadro. Si no lo lee, puedes escribir el código manualmente.",
                    "Si el escáner no abre, revisa en Ajustes de Android que SENE VENTAS tenga permiso de Cámara. Cierra y vuelve a abrir la app después de concederlo.",
                    "También puedes capturar el código en el campo Código de barras y pulsar AGREGAR AL TICKET. Verifica que no falte ni sobre ningún dígito."
                ).random()
            listOf("pago insuficiente", "no alcanza", "falta dinero", "pago incompleto", "cliente dio menos", "me dio menos").any { it in q } ->
                listOf(
                    "Si el efectivo recibido es menor que el total, SENE muestra cuánto falta y no registra la venta. Captura el importe correcto que entrega el cliente y cobra solo cuando cubra el total.",
                    "Revisa el TOTAL del ticket y el efectivo recibido. La app calcula automáticamente el cambio o cuánto falta; no confirma una venta con pago insuficiente.",
                    "No registres un cobro por fuera para cuadrar la caja. Corrige el importe recibido en el campo de pago antes de pulsar COBRAR Y REGISTRAR VENTA."
                ).random()
            listOf("whatsapp no abre", "no abre whatsapp", "no puedo enviar", "no se envia", "no se envía", "cambiar numero", "cambiar número", "numero de reportes", "número de reportes").any { it in q } ->
                listOf(
                    "El número que recibe los reportes se configura desde Soy Patrón > CAMBIAR NÚMERO DE WHATSAPP. Captúralo con código de país, solo dígitos, y guarda. Para compartir necesitas tener WhatsApp instalado y conexión a Internet.",
                    "Si WhatsApp no abre, comprueba que esté instalado y actualizado. Revisa también que el número configurado en Soy Patrón incluya el código de país y sea correcto.",
                    "SENE prepara el texto del reporte y abre WhatsApp para que lo compartas. Revisa el destinatario y confirma el envío dentro de WhatsApp; la app no puede confirmar por sí sola que el mensaje llegó."
                ).random()
            listOf("iniciar turno", "comenzar turno", "encender y comenzar", "abrir turno", "entrar a ventas").any { it in q } ->
                listOf(
                    "En Inicio de Turno escribe el nombre y celular del trabajador y el efectivo inicial de caja chica. Después pulsa ENCENDER Y COMENZAR TURNO. La app prepara el respaldo del inventario y el reporte de inicio.",
                    "Antes de vender, inicia el turno con los datos del trabajador y cuánto dinero recibe para empezar. Revisa que el inventario esté cargado y confirma con ENCENDER Y COMENZAR TURNO.",
                    "El turno comienza registrando quién trabajará, su número y el fondo inicial. No pongas como caja inicial el dinero que esperas vender: captura únicamente el efectivo que entregas al inicio."
                ).random()
            listOf("cargar txt", "cargar desde txt", "importar txt", "archivo txt", "inventario desde txt", "cargar inventario").any { it in q } ->
                listOf(
                    "La carga del inventario TXT se hace desde Soy Patrón (PIN). Entra a Cargar inventario desde TXT, selecciona el archivo de inventario y espera la confirmación. Después el catálogo queda guardado en el teléfono para trabajar en VENTAS.",
                    "Para sustituir o cargar el catálogo, entra a Soy Patrón, abre Cargar inventario desde TXT y elige el archivo correcto. Verifica que sea el respaldo de inventario que deseas usar antes de confirmar.",
                    "El trabajador no necesita importar el TXT al comenzar cada turno si el inventario ya está cargado en el teléfono. La carga y administración del archivo corresponden a Soy Patrón."
                ).random()
            listOf("escanear", "lector", "codigo de barras", "código de barras", "buscar producto").any { it in q } ->
                listOf(
                    "En VENTAS pulsa ESCANEAR PRODUCTO y apunta la cámara al código de barras. Cuando lo lea, revisa el producto y agrégalo al ticket. También puedes escribir el código manualmente.",
                    "Si el lector no captura el código, puedes escribirlo en el campo Código de barras y pulsar AGREGAR AL TICKET. Comprueba que el código corresponda al producto que entregas.",
                    "Escanea cada artículo que el cliente llevará. Si el producto está registrado y tiene existencia, se agrega al ticket; si no está registrado o está agotado, la app ofrece la opción de recepción de proveedor."
                ).random()
            listOf("hacer una venta", "realizar una venta", "cobrar", "cobro", "ticket", "cuanto me pagan", "cuánto me pagan", "dar cambio").any { it in q } ->
                listOf(
                    "Para cobrar, escanea los productos para formar el ticket. Captura cuánto te entrega el cliente y revisa TOTAL y Cambio a dar. Pulsa COBRAR Y REGISTRAR VENTA solo cuando el pago sea suficiente.",
                    "Agrega al ticket únicamente los productos que el cliente comprará. Antes de cobrar, verifica cantidades, total y efectivo recibido. La app calcula el cambio y descuenta las piezas del inventario al guardar la venta.",
                    "Si el cliente entrega más dinero que el total, SENE calcula el cambio. Si entrega menos, te muestra cuánto falta y no registra la venta hasta que el pago cubra el total."
                ).random()
            listOf("limpiar ticket", "borrar ticket", "quitar ticket", "me equivoque", "me equivoqué", "cancelar venta").any { it in q } ->
                listOf(
                    "Si todavía no has cobrado, usa Limpiar ticket para vaciar los artículos capturados y empezar de nuevo. Hazlo antes de registrar la venta.",
                    "Revisa el ticket antes de cobrar. Si aún no se ha registrado, puedes limpiarlo y volver a escanear los productos correctos. Si ya se cobró, no intentes corregirlo creando otra venta; solicita al patrón que revise el movimiento.",
                    "La opción Limpiar ticket solo borra los artículos pendientes de cobro; no elimina ventas que ya quedaron registradas."
                ).random()
            listOf("pagar proveedor", "pago proveedor", "pago a proveedor", "abonar proveedor", "gasto proveedor").any { it in q } ->
                listOf(
                    "Al registrar Llegó proveedor puedes indicar cuánto pagarás en ese momento. La app descuenta ese importe de la caja y registra el pago como movimiento del turno. Si no pagarás ahora, captura 0.",
                    "Antes de confirmar el ingreso, escribe el importe que realmente entregarás al proveedor. No puede superar el efectivo disponible en caja. Revisa el resumen dos veces porque el registro no se puede deshacer.",
                    "Los pagos a proveedores se descuentan de la caja disponible y aparecen en el resumen y reporte del turno. La recepción de mercancía y el pago quedan registrados como movimientos."
                ).random()
            listOf("cerrar turno", "finalizar turno", "terminar turno", "cierre de turno", "reporte final", "enviar reporte", "mandar reporte", "whatsapp").any { it in q } ->
                listOf(
                    "Para terminar, usa la opción de reporte o cierre de turno en VENTAS. SENE prepara el reporte con ventas, pagos a proveedores y el inventario actualizado; después permite compartirlo por WhatsApp. Verifica que se haya guardado y enviado.",
                    "El reporte de cierre reúne los movimientos del turno y el stock al momento del respaldo. Si es la primera vez, la app puede pedir elegir la carpeta donde guardará los TXT. Sigue el selector y concede permiso a la carpeta.",
                    "Antes de cerrar, revisa ventas y pagos a proveedores. Genera el reporte final y compártelo al contacto de WhatsApp configurado por el patrón. Conserva el TXT como respaldo."
                ).random()
            listOf("soy patron", "soy patrón", "administrador", "pin", "cambiar whatsapp", "numero de whatsapp", "número de whatsapp").any { it in q } ->
                listOf(
                    "Soy Patrón es el área administrativa protegida por PIN. Desde ahí se puede crear el inventario inicial, administrar productos, consultar el inventario, guardar un TXT, cargar inventario desde TXT y cambiar el número de WhatsApp para reportes.",
                    "Las tareas administrativas se realizan desde Soy Patrón y requieren el PIN. El trabajador debe usar VENTAS para atender clientes y registrar recepciones permitidas; no debe modificar el catálogo ni sus precios.",
                    "Si necesitas cambiar el WhatsApp que recibe los reportes, entra a Soy Patrón, pulsa Cambiar número de WhatsApp, captura el número con código de país y guarda."
                ).random()
            listOf("hora de internet", "hora del celular", "reloj", "fecha y hora", "no avanza la hora").any { it in q } ->
                listOf(
                    "SENE muestra por separado la hora del celular y la hora de Internet. La hora del teléfono avanza continuamente; la de Internet se consulta al iniciar y después se mantiene avanzando con el reloj interno.",
                    "Si la hora de Internet no está disponible, revisa la conexión. La hora del celular sigue mostrándose y los reportes conservan la fecha y hora registradas por el dispositivo.",
                    "En Inicio de Turno puedes consultar la hora de Internet. En VENTAS también se muestran ambos relojes para que puedas comparar la hora del teléfono con la referencia de Internet."
                ).random()
            listOf("que puede hacer la ia", "qué puede hacer la ia", "inteligencia artificial", "chat ia", "chat sene", "preguntarte").any { it in q } ->
                listOf(
                    "Puedo orientarte sobre el uso de SENE VENTAS y consultar los registros locales de ventas, movimientos, caja e inventario. No puedo cobrar, modificar existencias ni cerrar el turno desde este chat.",
                    "Pregúntame cómo realizar una tarea o consulta los datos guardados en este teléfono. Mis respuestas se basan en las funciones y registros disponibles en la app; no sustituyo la autorización del patrón.",
                    "Puedo explicarte un procedimiento y ayudarte a revisar información registrada. Por seguridad, el chat no ejecuta ventas, pagos, cambios de inventario ni operaciones administrativas."
                ).random()
            else -> null
        }
        if (workflowHelp != null) return workflowHelp
        if (listOf("qué fecha", "que fecha", "qué día", "que dia", "fecha de hoy", "qué hora", "que hora").any { it in q })
            return "Hoy es " + today + ". La hora del teléfono y la hora de Internet se muestran por separado en SENE VENTAS."
        if (cashQuery) {
            if (shift == null) return "No hay un turno activo. La caja se calcula al iniciar un turno."
            val start = shift.optLong("startTs", 0L)
            var sold = 0.0
            for (i in 0 until sales.length()) { val x = sales.optJSONObject(i) ?: continue; if (x.optLong("ts", 0L) >= start) sold += x.optDouble("subtotal") }
            val expenses = Store.providerExpenses(a, start); val opening = shift.optDouble("cash")
            return "RESUMEN DE CAJA\nTrabajador: " + shift.optString("worker") + "\nCaja inicial: " + Store.money(opening) +
                "\nVentas del turno: " + Store.money(sold) + "\nPagos a proveedores: " + Store.money(expenses) +
                "\nCAJA ACTUAL ESTIMADA: " + Store.money(opening + sold - expenses)
        }
        if (salesQuery) {
            var total = 0.0; var units = 0; var count = 0
            val names = linkedMapOf<String, Int>()
            for (i in 0 until sales.length()) {
                val x = sales.optJSONObject(i) ?: continue; val ts = x.optLong("ts", 0L)
                if (ts in from until until || (ts == 0L && x.optString("time").startsWith(label))) {
                    total += x.optDouble("subtotal"); units += x.optInt("qty", 1); count++
                    val name = x.optString("name", "Producto"); names[name] = (names[name] ?: 0) + x.optInt("qty", 1)
                }
            }
            if (count == 0) return "No encuentro ventas registradas para el " + label + "."
            val detail = names.entries.sortedByDescending { it.value }.take(8).joinToString("\n") { "• " + it.key + ": " + it.value + " pza(s)" }
            return "VENTAS DEL " + label + "\nTotal: " + Store.money(total) + "\nRenglones: " + count + "\nPiezas: " + units + "\n\nProductos con movimiento:\n" + detail
        }
        if (moveQuery) {
            val found = mutableListOf<String>(); var paid = 0.0
            for (i in 0 until moves.length()) {
                val x = moves.optJSONObject(i) ?: continue; val ts = x.optLong("ts", 0L)
                if (!(ts in from until until || (ts == 0L && x.optString("time").startsWith(label)))) continue
                if (x.optString("type") == "PROVIDER_PAYMENT") paid += x.optDouble("amount")
                found.add("• " + x.optString("time") + ": " + x.optString("text", "Movimiento registrado"))
            }
            if (found.isEmpty()) return "No encuentro movimientos registrados para el " + label + ". Puedes pedirme otra fecha en formato DD/MM/AAAA."
            return "MOVIMIENTOS DEL " + label + "\n" + found.takeLast(30).joinToString("\n") + "\n\nPagos a proveedores: " + Store.money(paid)
        }
        if (stockQuery) {
            if (products.length() == 0) return "Todavía no hay inventario cargado."
            val search = Regex("""(?:de|del|para)\s+(.+)$""").find(q)?.groupValues?.get(1)?.trim()
            if (!search.isNullOrBlank() && search !in listOf("hoy", "ayer", "inventario", "stock")) {
                for (i in 0 until products.length()) {
                    val p = products.optJSONObject(i) ?: continue
                    if (p.optString("name").lowercase(locale).contains(search) || p.optString("code").contains(search))
                        return "INVENTARIO · " + p.optString("name") + "\nCódigo: " + p.optString("code") + "\nExistencia: " + p.optInt("stock") + "\nPrecio: " + Store.money(p.optDouble("price"))
                }
            }
            val low = mutableListOf<String>(); var units = 0
            for (i in 0 until products.length()) { val p = products.optJSONObject(i) ?: continue; units += p.optInt("stock"); if (p.optInt("stock") <= 3) low.add(p.optString("name") + ": " + p.optInt("stock")) }
            return "INVENTARIO ACTUAL\nProductos: " + products.length() + "\nPiezas: " + units + "\nÚltima actualización: " + Store.stockUpdatedAtText(a) +
                "\n\n" + if (low.isEmpty()) "No detecto productos con 3 piezas o menos." else "Existencias bajas:\n" + low.take(15).joinToString("\n")
        }
        if (listOf("cómo", "como ", "ayuda", "para qué", "funciona", "usar", "aplicación", "app", "sene ventas").any { it in q })
            return "Puedo consultar ventas por día, movimientos, pagos a proveedores, caja e inventario. Ejemplos:\n• ¿Cuánto vendí hoy?\n• ¿Qué movimientos hubo ayer?\n• Ventas del 05/11/2026\n• ¿Cuánto queda en caja?\n• ¿Qué productos tienen poco stock?\n\nNo hago cobros, cambios de inventario ni cierres desde el chat."
        if (listOf("fecha", "hoy", "ayer", "anteayer", "antier", "/", "mañana").any { it in q })
            return "Hoy es " + today + ". Para consultar otro día escribe la fecha completa DD/MM/AAAA, por ejemplo: «ventas del 05/11/2026»."
        return "Puedo ayudarte con ventas, movimientos, pagos a proveedores, caja e inventario. Prueba: «¿Cuánto vendí hoy?», «¿Qué movimientos hubo ayer?» o «¿Cuánto queda en caja?». Mis respuestas se basan en los registros locales de esta app."
    }
}

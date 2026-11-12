package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Conocimiento conversacional aprobado para SENE IA.
 * Se inicializa una sola vez en almacenamiento local; nunca incluye resultados web.
 * Las cinco respuestas se conservan por pregunta y se muestra la variante principal.
 */
internal object SeneApprovedKnowledge {
    private const val PREFS = "sene_ia_knowledge"
    private const val KEY = "approved_knowledge_v1"

    private data class Entry(
        val id: Int,
        val question: String,
        val primary: Int,
        val aliases: List<String>,
        val answers: List<String>
    )

    private val entries = listOf(
        Entry(1, "¿Qué puedo hacer en Ventas?", 5,
            listOf("que puedo hacer en ventas", "que puedo hacer desde ventas"),
            listOf(
                "En Ventas puedes registrar las operaciones que realizas, agregar productos mediante búsqueda o escáner, consultar artículos y mantener actualizado el registro de tus movimientos.",
                "En Ventas puedes registrar ventas, escanear códigos de barras, buscar productos registrados, identificar productos no registrados y agregar productos cuando tengas autorización.",
                "Desde aquí puedes trabajar con productos, registrar ventas, escanear mercancía y consultar información relacionada con tus operaciones. Si encuentras un producto que no existe, puedes iniciar su registro en inventario cuando tengas autorización.",
                "Puedo ayudarte a entender cómo registrar una venta, localizar productos, utilizar el escáner, identificar mercancía nueva y conocer cómo se relacionan las ventas con el inventario.",
                "El módulo Ventas centraliza el trabajo de venta y el registro de operaciones. Facilita la búsqueda de productos, el uso del lector de códigos y la incorporación de mercancía que todavía no está registrada."
            )
        ),
        Entry(2, "¿Dónde corrijo si me equivoco?", 5,
            listOf("donde corrijo si me equivoco", "donde corrijo un error", "me equivoque en una venta"),
            listOf(
                "Busca el registro correspondiente y verifica si tienes una opción para editarlo o corregirlo. Si no aparece, solicita ayuda al administrador.",
                "Localiza la operación en el historial de ventas y revisa las opciones disponibles. Antes de registrar otra operación para compensarla, verifica qué necesitas corregir.",
                "Revisa el inventario y, si no tienes permisos para corregirlo, pide al administrador que revise el movimiento.",
                "Dime qué dato está mal: una venta, un producto, una cantidad o un movimiento. Así puedo orientarte sobre el módulo correcto.",
                "SENE VENTAS organiza tus operaciones para que puedas identificar los registros que necesitan atención. La posibilidad de modificarlos depende de tus permisos y de las herramientas disponibles en cada módulo."
            )
        ),
        Entry(3, "¿Cuánto tenemos en caja?", 4,
            listOf("cuanto tenemos en caja", "cuanto hay en caja", "saldo de caja"),
            listOf(
                "Consulta el saldo actual de caja registrado en el turno.",
                "Puedo ayudarte a revisar cuánto dinero hay en caja según los movimientos registrados.",
                "En el módulo de caja revisa ingresos, retiros, movimientos y saldo disponible, de acuerdo con tus permisos.",
                "Solo pregunta «¿Cuánto tenemos en caja?» y SENE IA consultará los registros locales para mostrarte el saldo correspondiente.",
                "SENE VENTAS facilita el control de caja mediante el registro de operaciones y movimientos, para consultar la información financiera del turno."
            )
        ),
        Entry(4, "¿Cuánto se vendió tal día?", 4,
            listOf("cuanto se vendio tal dia", "cuanto vendimos el", "ventas del dia", "ventas del dia"),
            listOf(
                "Puedes consultar el importe de las ventas de cualquier fecha en el historial diario.",
                "Dime qué día quieres revisar y te ayudaré a conocer el total de ventas registrado.",
                "SENE VENTAS permite consultar los registros de ventas por fecha para conocer los importes acumulados de cada día.",
                "Solo pregunta, por ejemplo: «¿Cuánto vendimos el 5 de noviembre?». SENE IA buscará en los registros locales y te mostrará el total vendido en esa fecha.",
                "Puedes dar seguimiento a las operaciones de venta y revisar el desempeño de cada día."
            )
        ),
        Entry(5, "Estoy aburrido", 4,
            listOf("estoy aburrido", "estoy aburrida", "me aburro"),
            listOf(
                "¡Ánimo! Siempre hay algo interesante que hacer. ¿Quieres que conversemos un rato?",
                "¡Eso tiene solución! 😁 ¿Te cuento un chiste o te pongo un acertijo?",
                "Aquí estoy para conversar contigo. ¿Cómo va tu jornada?",
                "¡Vamos a cambiar ese ánimo! Podemos hablar de cualquier tema, jugar o descubrir algo interesante.",
                "Si ya terminaste tus tareas pendientes, puedes revisar cómo va tu día, tus ventas o el inventario."
            )
        ),
        Entry(6, "¿Cómo elimino una venta?", 4,
            listOf("como elimino una venta", "quiero eliminar una venta", "borrar una venta"),
            listOf(
                "Busca la venta en el historial y revisa si tienes disponible la opción para eliminarla. Si no aparece, consulta al administrador.",
                "La eliminación o corrección de una venta depende de tus permisos y de las herramientas disponibles en SENE VENTAS.",
                "Antes de eliminar, verifica que sea la operación correcta; los cambios pueden afectar registros relacionados.",
                "Dime qué venta necesitas eliminar y te ayudaré a identificar dónde localizarla y qué opciones tienes disponibles según tus permisos.",
                "SENE VENTAS organiza las operaciones comerciales. Para corregir una venta, revisa las herramientas administrativas y los permisos correspondientes."
            )
        ),
        Entry(7, "¿Qué sabes hacer?", 4,
            listOf("que sabes hacer", "que sabes hacer tu", "que puedes hacer tu"),
            listOf(
                "Puedo ayudarte con ventas, inventario, caja, movimientos y consultas sobre el funcionamiento de SENE VENTAS.",
                "¡Muchas cosas! Puedo responder tus preguntas, ayudarte a encontrar información y orientarte cuando tengas alguna duda sobre tu trabajo.",
                "Puedo ayudarte a consultar ventas, revisar existencias, conocer movimientos de caja, buscar productos y resolver dudas sobre la aplicación.",
                "¡Soy SENE IA! Puedo conversar contigo, entender tus preguntas y ayudarte a utilizar SENE VENTAS. También puedo consultar información de los registros locales y orientarte en tus actividades.",
                "Soy tu asistente inteligente integrado en SENE VENTAS. Estoy diseñado para facilitar las operaciones diarias, ayudarte a consultar información y hacer más sencillo el control de tu trabajo."
            )
        ),
        Entry(8, "¿Qué haces?", 5,
            listOf("que haces", "que haces tu", "quien eres"),
            listOf(
                "Soy el asistente de SENE VENTAS. Estoy aquí para ayudarte con tus dudas sobre la aplicación y sus operaciones.",
                "¡Te acompaño durante tu jornada! Puedo conversar contigo, responder preguntas y ayudarte cuando tengas alguna duda.",
                "Mi función es facilitar el uso de SENE VENTAS, proporcionando información sobre ventas, inventario, caja y movimientos registrados.",
                "Soy SENE IA, tu asistente virtual. Escucho tus preguntas, interpreto lo que necesitas y te ayudo a encontrar respuestas utilizando el conocimiento de la aplicación y sus registros locales.",
                "¡Soy SENE IA! 🤖 Estoy aquí para ayudarte, resolver tus dudas, facilitar tu trabajo y hacer que utilizar SENE VENTAS sea más sencillo y agradable."
            )
        ),
        Entry(9, "Explícame cómo vender", 3,
            listOf("explicame como vender", "como vender", "como hago una venta", "guiame para vender"),
            listOf(
                "Para vender, ingresa al módulo Ventas, busca el producto que deseas vender, agrégalo a la operación y registra la venta siguiendo las opciones disponibles en pantalla.",
                "¡Claro! Te explico: entra a Ventas, busca el producto o escanea su código, selecciona el artículo, indica la cantidad, revisa los productos agregados y confirma la operación utilizando las opciones disponibles.",
                "¡Con gusto! Te puedo acompañar durante el proceso. Primero entra a Ventas y dime qué aparece en tu pantalla. Te iré indicando qué hacer.",
                "¡Vamos a realizar tu primera venta! SENE VENTAS te permite localizar productos, agregarlos a una operación y registrar tus ventas. Si tienes alguna dificultad, dime en qué paso estás y te ayudaré.",
                "El proceso de venta consiste en seleccionar los productos solicitados, verificar cantidades e importes y registrar la operación. Es importante revisar los datos antes de confirmar."
            )
        ),
        Entry(10, "No le entiendo a la aplicación", 3,
            listOf("no le entiendo a la aplicacion", "no entiendo la aplicacion", "no entiendo como funciona la app"),
            listOf(
                "¡No te preocupes! Estoy aquí para ayudarte. Dime qué parte de la aplicación se te está dificultando y te explicaré cómo utilizarla.",
                "¡Con gusto te ayudo! Vamos poco a poco. Dime en qué módulo estás y te explicaré qué hacer, paso a paso.",
                "No te preocupes, aprenderemos juntos. Primero dime qué aparece en tu pantalla y te iré guiando hasta que puedas realizar lo que necesitas.",
                "¡Para eso estoy! 🤖 Puedo orientarte sobre las funciones de SENE VENTAS. Cuéntame qué quieres hacer o qué parte no entiendes y buscaremos la manera más sencilla.",
                "SENE VENTAS está diseñado para facilitar tus actividades. Si tienes dificultades para utilizarlo, puedo orientarte en el funcionamiento de sus módulos y operaciones."
            )
        ),
        Entry(11, "¿A qué hora salgo?", 2,
            listOf("a que hora salgo", "hora de salida", "cuando termina mi jornada"),
            listOf(
                "Puedo ayudarte a consultar tu horario de salida. Déjame verificar si tienes un horario registrado.",
                "¡Claro! 😊 Vamos a revisar tu horario para saber a qué hora termina tu jornada.",
                "Solo pregúntame «¿A qué hora salgo?» y consultaré tu horario registrado para darte la hora de salida correspondiente.",
                "Tu hora de salida depende del turno que tengas asignado. Si tu horario no aparece registrado, podemos consultar con el administrador.",
                "SENE IA puede orientarte sobre tus horarios laborales. La hora de salida deberá verificarse conforme al turno asignado y a la información disponible."
            )
        ),
        Entry(12, "¿Qué puedes mejorar?", 5,
            listOf("que puedes mejorar", "que mejorarias", "como puedes mejorar"),
            listOf(
                "Puedo mejorar aprendiendo de las preguntas y respuestas que se aprueban, para comprenderte mejor y ofrecerte respuestas cada vez más útiles.",
                "Puedo ayudarte a trabajar más fácilmente, orientarte en ventas, inventario, caja y consultas, y explicarte los procesos paso a paso.",
                "Puedo ampliar mis conocimientos sobre SENE VENTAS, aprender nuevas formas de responder y mejorar mi capacidad para entender lo que necesitas.",
                "¡Muchas cosas! 🤖 Puedo mejorar mi manera de conversar, comprender tus preguntas, ayudarte a resolver problemas y orientarte mejor durante tu jornada.",
                "Mi objetivo es evolucionar contigo: mejorar mis respuestas, aprender de las experiencias aprobadas, comprender mejor SENE VENTAS y facilitar cada vez más tus actividades."
            )
        )
    )

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale("es", "MX")), Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun seed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return
        val array = JSONArray()
        entries.forEach { entry ->
            val answers = JSONArray()
            entry.answers.forEach { answers.put(it) }
            val aliases = JSONArray()
            entry.aliases.forEach { aliases.put(it) }
            array.put(JSONObject().put("id", entry.id).put("question", entry.question)
                .put("primary", entry.primary).put("aliases", aliases).put("answers", answers))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun answer(context: Context, raw: String): String? {
        seed(context.applicationContext)
        val q = normalize(raw)
        if (q.isBlank()) return null

        // Las consultas que requieren datos reales siempre se resuelven con Store, no con frases genéricas.
        if (listOf("cuanto tenemos en caja", "cuanto hay en caja", "saldo de caja").any { q.contains(normalize(it)) }) return null
        if (listOf("cuanto se vendio", "cuanto vendimos", "ventas del dia", "ventas de hoy", "ventas de ayer").any { q.contains(normalize(it)) }) return null

        val match: Entry = entries
            .asSequence()
            .filter { entry -> entry.id !in setOf(3, 4, 11) }
            .mapNotNull { entry ->
                val score: Int = (entry.aliases + entry.question)
                    .map { alias ->
                        val normalizedAlias = normalize(alias)
                        when {
                            q == normalizedAlias -> 100
                            normalizedAlias.length >= 10 && q.contains(normalizedAlias) -> 90
                            normalizedAlias.length >= 10 && normalizedAlias.contains(q) && q.length >= 10 -> 80
                            else -> 0
                        }
                    }
                    .maxOrNull() ?: 0
                if (score >= 80) entry to score else null
            }
            .maxByOrNull { scored -> scored.second }
            ?.first ?: return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until stored.length()) {
            val item = stored.optJSONObject(i) ?: continue
            if (item.optInt("id") != match.id) continue
            val answers = item.optJSONArray("answers") ?: return null
            return answers.optString(item.optInt("primary", match.primary) - 1).takeIf { it.isNotBlank() }
        }
        return match.answers.getOrNull(match.primary - 1)
    }

    fun shiftExitAnswer(context: Context): String? {
        val qShift = Store.shift(context) ?: return null
        val scheduled = listOf("scheduledEnd", "endTime", "horaSalida", "hora_salida")
            .map { qShift.optString(it).trim() }.firstOrNull { it.isNotBlank() }
        return if (scheduled != null) {
            "Tu hora de salida registrada para este turno es: $scheduled."
        } else {
            "¡Claro! 😊 Vamos a revisar tu horario para saber a qué hora termina tu jornada. No encuentro una hora de salida registrada en este turno. Consulta al patrón o administrador para confirmar tu horario."
        }
    }
}

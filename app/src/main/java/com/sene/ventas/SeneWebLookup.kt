package com.sene.ventas

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Búsqueda web gratuita sin clave, cuenta ni facturación.
 * Las consultas y respuestas se mantienen solo en memoria de esta sesión.
 */
internal object SeneWebLookup {
    fun shouldSearch(question: String): Boolean {
        val q = question.lowercase(java.util.Locale("es", "MX"))
        // La hora de Internet es una consulta local de reloj, no una búsqueda web.
        if (listOf("hora de internet", "hora del internet", "reloj de internet").any { it in q }) return false
        return Regex("""\b(internet|intern[eé]t|web|nube|red|online|en línea)\b""").containsMatchIn(q) ||
            listOf("busca en", "buscar en", "investiga en", "consulta en", "averigua en", "qué hay en internet",
                "que hay en internet", "información actualizada", "noticias de hoy").any { it in q }
    }

    fun search(question: String): String? {
        val query = question.trim().take(240)
        if (query.isBlank()) return null
        val snippets = searchDuckDuckGoHtml(query)
        if (snippets.isNotEmpty()) {
            return "INVESTIGACIÓN EN INTERNET\n" +
                snippets.take(5).joinToString("\n\n") +
                "\n\nResumen de resultados web. No se guardó esta consulta en el historial de SENE VENTAS."
        }
        return searchInstantAnswer(query)
    }

    private fun searchDuckDuckGoHtml(query: String): List<String> {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://html.duckduckgo.com/html/?q=$encoded"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            }
            if (connection.responseCode !in 200..299) return emptyList()
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val linkRegex = Regex("""(?is)<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
            val snippetRegex = Regex("""(?is)<(?:a|div)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>""")
            val links = linkRegex.findAll(html).toList()
            val descriptions = snippetRegex.findAll(html).toList()
            links.mapIndexedNotNull { index, match ->
                val title = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("""\s+"""), " ").trim()
                val rawUrl = match.groupValues[1].replace("&amp;", "&")
                val parsed = android.net.Uri.parse(rawUrl)
                val destination = (parsed.getQueryParameter("uddg") ?: rawUrl).let {
                    if (it.startsWith("//")) "https:$it" else it
                }
                if (!destination.startsWith("https://", ignoreCase = true)) return@mapIndexedNotNull null
                val snippet = descriptions.getOrNull(index)?.groupValues?.get(1)?.let {
                    Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString()
                        .replace(Regex("""\s+"""), " ").trim()
                }.orEmpty()
                buildString {
                    if (title.isNotBlank()) append(title).append("\n")
                    if (snippet.length > 35) append(snippet).append("\n")
                    append("Fuente: ").append(destination)
                }
            }.distinct().take(5)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun searchInstantAnswer(query: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SENE-Ventas/1.0 (Android)")
            }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val answer = json.optString("AbstractText").trim().ifBlank {
                json.optString("Definition").trim().ifBlank { firstTopic(json.optJSONArray("RelatedTopics")) }
            }
            answer.takeIf { it.isNotBlank() }?.let {
                "INVESTIGACIÓN EN INTERNET\n" + it.take(900) +
                    "\n\nResumen de una respuesta instantánea gratuita. No se guardó en el historial de SENE VENTAS."
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstTopic(items: JSONArray?): String {
        if (items == null) return ""
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val text = item.optString("Text").trim()
            if (text.isNotBlank()) return text
            val nested = firstTopic(item.optJSONArray("Topics"))
            if (nested.isNotBlank()) return nested
        }
        return ""
    }
}

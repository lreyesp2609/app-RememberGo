package com.remembergo.app.websocket

import android.content.Context
import android.util.Log
import com.remembergo.app.utils.SessionManager
import com.remembergo.app.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Singleton para gestionar WebSocket de notificaciones globales
 * Mantiene actualizado el conteo de mensajes no leídos por grupo
 */
object NotificationWebSocketManager {
    private const val TAG = "WS_Notifications"

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private val gson = Gson()

    // 🆕 Para invocar refresh desde el listener
    private var appContext: Context? = null

    // 🆕 Guardar parámetros de conexión para reconexión
    private var savedBaseUrl: String? = null
    private var savedToken: String? = null
    private var isInitialized = false

    // Estado de mensajes no leídos por grupo (grupoId -> count)
    private val _unreadCounts = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<Int, Int>> = _unreadCounts

    // Estado de conexión
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // 🆕 Flag para saber si hay una conexión en curso
    private var isConnecting = false

    // 🆕 Flag para saber si es una reconexión automática
    private var isReconnecting = false

    private val internalListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "✅ ════════════════════════════════════════")
            Log.d(TAG, "✅ WEBSOCKET DE NOTIFICACIONES CONECTADO")
            Log.d(TAG, "✅ ════════════════════════════════════════")
            _isConnected.value = true
            isConnecting = false
            isReconnecting = false
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                Log.v(TAG, "📨 Mensaje recibido: ${text.take(100)}")
                val json = JSONObject(text)
                val type = json.optString("type")

                when (type) {
                    "unread_count_update" -> {
                        handleUnreadCountUpdate(json)
                    }
                    "pong" -> {
                        Log.v(TAG, "🏓 Pong recibido")
                    }
                    "token_refreshed" -> {
                        val message = json.optString("message")
                        Log.d(TAG, "🔄 Token actualizado en servidor: $message")
                    }
                    "error" -> {
                        val message = json.optString("message")
                        val code = json.optString("code")
                        Log.e(TAG, "❌ Error del servidor [$code]: $message")

                        if (code == "TOKEN_EXPIRED" || code == "INVALID_TOKEN") {
                            Log.e(TAG, "🔒 Token inválido [$code], disparando refresh...")
                            disconnect() // Desconectar pero mantener savedBaseUrl para reconectar luego
                            
                            // 🆕 Disparar el refresh de token activamente
                            appContext?.let { ctx ->
                                SessionManager.getInstance(ctx).refreshAccessToken(ctx)
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "⚠️ Tipo de mensaje no manejado: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error procesando mensaje: ${e.message}")
                e.printStackTrace()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ Error en WebSocket: ${t.message}")
            isConnecting = false
            _isConnected.value = false

            // 🆕 Trigger de refresh en Broken pipe
            if (t.message?.contains("Broken pipe") == true) {
                Log.w(TAG, "⚠️ Broken pipe detectado en Notificaciones. Solicitando refresh...")
                appContext?.let { ctx ->
                    SessionManager.getInstance(ctx).refreshAccessToken(ctx)
                }
            }

            // 🆕 Si es error 403 y NO estamos reconectando, reconectar con nuevo token
            if (response?.code == 403 && !isReconnecting && savedBaseUrl != null && savedToken != null) {
                Log.w(TAG, "⚠️ Error 403 Forbidden - Reconectando con token actualizado...")
                isReconnecting = true

                // Esperar un poco antes de reconectar
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    reconnectWithNewToken()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "⚠️ WebSocket cerrándose: $code - $reason")
            _isConnected.value = false
            isConnecting = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "🔒 WebSocket cerrado: $code - $reason")
            _isConnected.value = false
            isConnecting = false
        }
    }

    /**
     * 🆕 Inicializar y registrar listener en SessionManager
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "ℹ️ Ya está inicializado")
            return
        }

        appContext = context.applicationContext // 🆕 Guardar contexto

        Log.e(TAG, "════════════════════════════════════════")
        Log.e(TAG, "🚀 INICIALIZANDO NOTIFICATION WEBSOCKET MANAGER")
        Log.e(TAG, "════════════════════════════════════════")

        val sessionManager = SessionManager.getInstance(context)

        // Registrar listener PERMANENTE
        sessionManager.addTokenChangeListener { newToken ->
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "🔔 TOKEN ACTUALIZADO - NOTIFICANDO WS")
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "   Nuevo token: ${newToken.take(20)}...")

            onTokenUpdated(newToken)
        }

        isInitialized = true
        Log.e(TAG, "✅ Listener registrado en SessionManager")
        Log.e(TAG, "✅ Total listeners: ${sessionManager.getListenerCount()}")
        Log.e(TAG, "════════════════════════════════════════")
    }



    /**
     * 🆕 Reconectar con token actualizado
     */
    private fun reconnectWithNewToken() {
        Log.d(TAG, "🔄 ════════════════════════════════════════")
        Log.d(TAG, "🔄 RECONECTANDO CON TOKEN ACTUALIZADO")
        Log.d(TAG, "🔄 ════════════════════════════════════════")

        // Cerrar conexión actual
        webSocket?.close(1000, "Reconectando con nuevo token")
        webSocket = null
        _isConnected.value = false
        isConnecting = false

        // Reconectar con token guardado
        savedBaseUrl?.let { baseUrl ->
            savedToken?.let { token ->
                connectInternal(baseUrl, token)
            }
        }
    }

    /**
     * Conecta al WebSocket de notificaciones
     */
    fun connect(baseUrl: String, token: String) {
        // 🆕 Guardar parámetros
        savedBaseUrl = baseUrl
        savedToken = token

        if (isConnected()) {
            Log.d(TAG, "⚠️ Ya está conectado, actualizando token...")
            updateToken(token)
            return
        }

        if (isConnecting) {
            Log.d(TAG, "⏳ Ya hay una conexión en proceso, ignorando...")
            return
        }

        connectInternal(baseUrl, token)
    }

    /**
     * 🆕 Lógica interna de conexión
     */
    private fun connectInternal(baseUrl: String, token: String) {
        Log.d(TAG, "🔌 ════════════════════════════════════════")
        Log.d(TAG, "🔌 CONECTANDO WEBSOCKET DE NOTIFICACIONES")
        Log.d(TAG, "🔌 ════════════════════════════════════════")

        isConnecting = true

        // 🔥 ENVIAR TOKEN EN QUERY PARAMS (más confiable para WebSocket)
        val wsUrl = baseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
                "/ws/notificaciones?token=$token"  // <-- AGREGAR TOKEN AQUÍ

        Log.d(TAG, "   URL: ${wsUrl.replace(token, "***TOKEN***")}")  // Log seguro

        client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        // No necesitas el header Authorization si usas query params
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client?.newWebSocket(request, internalListener)
    }
    /**
     * 🆕 Callback cuando el token se actualiza
     */
    private fun onTokenUpdated(newToken: String) {
        Log.d(TAG, "🔄 ════════════════════════════════════════")
        Log.d(TAG, "🔄 TOKEN ACTUALIZADO POR SESSIONMANAGER")
        Log.d(TAG, "🔄 ════════════════════════════════════════")
        Log.d(TAG, "   Nuevo token: ${newToken.take(20)}...")

        savedToken = newToken

        if (isConnected()) {
            Log.d(TAG, "   ✅ WebSocket conectado: Enviando refresh")
            updateToken(newToken)
        } else if (isConnecting) {
            Log.d(TAG, "   ⏳ Ya hay una conexión en proceso (triggered by token update), ignorando...")
        } else {
            Log.d(TAG, "   ⚠️ WebSocket NO conectado")

            // Si hay una base URL guardada, reconectar
            if (savedBaseUrl != null) {
                Log.d(TAG, "   🔄 Reconectando automáticamente...")
                connectInternal(savedBaseUrl!!, newToken)
            } else {
                Log.d(TAG, "   ℹ️ No hay base URL, esperando connect() explícito")
            }
        }
    }
    /**
     * Actualizar token sin reconectar
     */
    fun updateToken(newToken: String) {
        if (!isConnected()) {
            Log.w(TAG, "⚠️ No conectado, no se puede actualizar token")
            return
        }

        Log.d(TAG, "🔄 Enviando token actualizado al servidor...")

        val message = """
        {
          "action": "refresh_token",
          "data": { "token": "$newToken" }
        }
    """.trimIndent()

        val sent = send(message)
        if (sent) {
            Log.d(TAG, "✅ Token enviado al servidor")
        } else {
            Log.e(TAG, "❌ Error al enviar token")
        }
    }


    /**
     * Procesa actualizaciones de mensajes no leídos
     */
    private fun handleUnreadCountUpdate(json: JSONObject) {
        try {
            val data = json.getJSONArray("data")
            val newCounts = mutableMapOf<Int, Int>()

            Log.d(TAG, "📊 ════════════════════════════════════════")
            Log.d(TAG, "📊 ACTUALIZACIÓN DE MENSAJES NO LEÍDOS")
            Log.d(TAG, "📊 ════════════════════════════════════════")

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val grupoId = item.getInt("grupo_id")
                val count = item.getInt("mensajes_no_leidos")
                newCounts[grupoId] = count
                Log.d(TAG, "   Grupo $grupoId: $count mensajes")
            }

            _unreadCounts.value = newCounts
            Log.d(TAG, "✅ Conteos actualizados correctamente")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando conteos: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Envía un mensaje al servidor
     */
    fun send(message: String): Boolean {
        return try {
            val sent = webSocket?.send(message) ?: false
            if (sent) {
                Log.v(TAG, "📤 Mensaje enviado: ${message.take(50)}...")
            } else {
                Log.w(TAG, "⚠️ No se pudo enviar mensaje (WebSocket no conectado)")
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al enviar mensaje: ${e.message}")
            false
        }
    }

    /**
     * Verifica si está conectado
     */
    fun isConnected(): Boolean {
        return webSocket != null && _isConnected.value
    }

    /**
     * Cierra la conexión y limpia los parámetros (usar en Logout)
     */
    fun close() {
        Log.d(TAG, "🔒 ════════════════════════════════════════")
        Log.d(TAG, "🔒 CERRANDO Y LIMPIANDO WEBSOCKET")
        Log.d(TAG, "🔒 ════════════════════════════════════════")
        
        disconnect()
        
        // Limpiar parámetros solo en cierre explícito
        savedBaseUrl = null
        savedToken = null
    }

    /**
     * Solo desconecta el socket actual sin limpiar parámetros (para errores de token)
     */
    fun disconnect() {
        webSocket?.close(1000, "Desconexión temporal")
        webSocket = null
        _isConnected.value = false
        isConnecting = false
        isReconnecting = false
    }

}

// En algún Activity o Fragment
fun testWebSocketPing() {
    val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val wsUrl = BuildConfig.BASE_URL
        .removeSuffix("/")
        .replace("https://", "wss://")
        .replace("http://", "ws://") + "/ws/ping"
    val request = Request.Builder()
        .url(wsUrl)
        .build()

    val testSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("TEST_WS", "✅ ✅ ✅ Conexión exitosa sin auth")
            webSocket.send("hello")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("TEST_WS", "📨 Respuesta del servidor: $text")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("TEST_WS", "❌ Error: ${t.message}")
            Log.e("TEST_WS", "❌ Response code: ${response?.code}")
            Log.e("TEST_WS", "❌ Response body: ${response?.body?.string()}")
        }
    })
}

package com.remembergo.app.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.remembergo.app.models.User
import com.remembergo.app.network.RetrofitClient
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SessionManager private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recuerdago_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 🆕 Flag atómico para evitar múltiples refrescos simultáneos
    private val isRefreshing = AtomicBoolean(false)

    // 🆕 Listener para cambios de token
    private val tokenListeners = mutableListOf<(String) -> Unit>()

    companion object {
        private const val TAG = "WS_SessionManager"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also {
                    INSTANCE = it
                    Log.d(TAG, "🏗️ SessionManager Singleton creado")
                }
            }
        }
    }

    fun saveTokens(access: String, refresh: String) {
        Log.d(TAG, "💾 ========================================")
        Log.d(TAG, "💾 GUARDANDO NUEVOS TOKENS")
        Log.d(TAG, "💾 ========================================")
        Log.d(TAG, "   Access Token: ${access.take(20)}...")
        Log.d(TAG, "   Refresh Token: ${refresh.take(20)}...")
        Log.d(TAG, "   Listeners registrados: ${tokenListeners.size}")

        prefs.edit()
            .putString("ACCESS_TOKEN", access)
            .putString("REFRESH_TOKEN", refresh)
            .apply()

        // 🆕 Notificar a todos los listeners
        if (tokenListeners.isEmpty()) {
            Log.w(TAG, "⚠️ ========================================")
            Log.w(TAG, "⚠️ NO HAY LISTENERS REGISTRADOS")
            Log.w(TAG, "⚠️ El token no será enviado al WebSocket")
            Log.w(TAG, "⚠️ ========================================")
        } else {
            Log.d(TAG, "📢 Notificando a ${tokenListeners.size} listeners...")
            tokenListeners.forEachIndexed { index, listener ->
                try {
                    Log.d(TAG, "   📤 Notificando listener #${index + 1}...")
                    listener.invoke(access)
                    Log.d(TAG, "   ✅ Listener #${index + 1} notificado correctamente")
                } catch (e: Exception) {
                    Log.e(TAG, "   ❌ Error al notificar listener #${index + 1}: ${e.message}")
                    e.printStackTrace()
                }
            }
            Log.d(TAG, "✅ ========================================")
            Log.d(TAG, "✅ TODOS LOS LISTENERS NOTIFICADOS")
            Log.d(TAG, "✅ ========================================")
        }
    }

    fun addTokenChangeListener(listener: (String) -> Unit) {
        tokenListeners.add(listener)
        Log.d(TAG, "➕ ========================================")
        Log.d(TAG, "➕ LISTENER REGISTRADO")
        Log.d(TAG, "➕ Total de listeners: ${tokenListeners.size}")
        Log.d(TAG, "➕ ========================================")
    }

    fun removeTokenChangeListener(listener: (String) -> Unit) {
        val removed = tokenListeners.remove(listener)
        Log.d(TAG, "➖ ========================================")
        Log.d(TAG, "➖ LISTENER ${if (removed) "REMOVIDO" else "NO ENCONTRADO"}")
        Log.d(TAG, "➖ Total de listeners: ${tokenListeners.size}")
        Log.d(TAG, "➖ ========================================")
    }

    // 🆕 Método para verificar cuántos listeners hay
    fun getListenerCount(): Int {
        return tokenListeners.size
    }

    fun getAccessToken(): String? {
        val token = prefs.getString("ACCESS_TOKEN", null)
        if (token != null) {
            Log.v(TAG, "🔑 Token recuperado: ${token.take(20)}...")
        } else {
            Log.w(TAG, "⚠️ No hay token disponible")
        }
        return token
    }

    fun getRefreshToken(): String? = prefs.getString("REFRESH_TOKEN", null)

    fun saveLoginState(isLoggedIn: Boolean) {
        prefs.edit()
            .putBoolean("IS_LOGGED_IN", isLoggedIn)
            .apply()
        Log.d(TAG, "🔐 Estado de login actualizado: $isLoggedIn")
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean("IS_LOGGED_IN", false)

    fun saveUser(user: User) {
        val userJson = gson.toJson(user)
        prefs.edit()
            .putString("USER_DATA", userJson)
            .apply()
        Log.d(TAG, "👤 Usuario guardado: ${user.nombre} ${user.apellido}")
    }

    fun getUser(): User? {
        val userJson = prefs.getString("USER_DATA", null)
        return if (userJson != null) {
            try {
                gson.fromJson(userJson, User::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al parsear usuario: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    fun clear() {
        Log.d(TAG, "🧹 Limpiando sesión (manteniendo listeners)")
        // ❌ NO borrar listeners aquí - deben persistir
        // tokenListeners.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "✅ Sesión limpiada. Listeners preservados: ${tokenListeners.size}")
    }

    fun hasValidSession(): Boolean {
        return getRefreshToken() != null && isLoggedIn()
    }

    /**
     * 🆕 Intenta refrescar el access token usando el refresh token almacenado.
     * Si falla o el refresh token no existe, cierra la sesión.
     */
    fun refreshAccessToken(context: Context, onComplete: (Boolean) -> Unit = {}) {
        if (!isRefreshing.compareAndSet(false, true)) {
            Log.d(TAG, "⏳ Ya hay un refresco de token en curso, ignorando solicitud...")
            return
        }

        val refreshToken = getRefreshToken()
        if (refreshToken == null) {
            Log.e(TAG, "❌ No hay Refresh Token, cerrando sesión...")
            logoutAndRedirect(context)
            isRefreshing.set(false)
            onComplete(false)
            return
        }

        Log.d(TAG, "🔄 Iniciando refresco de token...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = RetrofitClient.apiService.refreshToken(refreshToken)

                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    Log.d(TAG, "✅ Token refrescado exitosamente")
                    // saveTokens ya notifica a los listeners registrados
                    saveTokens(loginResponse.accessToken, loginResponse.refreshToken)
                    isRefreshing.set(false)
                    onComplete(true)
                } else {
                    Log.e(TAG, "❌ Error al refrescar token: ${response.code()} ${response.message()}")
                    logoutAndRedirect(context)
                    isRefreshing.set(false)
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Excepción al refrescar token: ${e.message}")
                isRefreshing.set(false)
                onComplete(false)
            }
        }
    }

    /**
     * 🆕 Limpia la sesión y redirige al usuario al Login
     */
    private fun logoutAndRedirect(context: Context) {
        clear()
        saveLoginState(false)
        
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        Log.w(TAG, "🚪 Sesión cerrada por error de autenticación")
    }

}
package com.remembergo.app.screen.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.remembergo.app.R
import com.remembergo.app.repository.LoginState
import com.remembergo.app.screen.components.AppButton
import com.remembergo.app.screen.components.AppTextField
import com.remembergo.app.screen.components.LanguageSelector
import com.remembergo.app.ui.theme.getBackgroundGradient
import com.remembergo.app.viewmodel.AuthViewModel
import com.remembergo.app.viewmodel.NotificationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    authViewModel: AuthViewModel,
    notificationViewModel: NotificationViewModel
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Observa el usuario
    LaunchedEffect(authViewModel.user) {
        if (authViewModel.user != null) {
            navController.navigate("home") {
                popUpTo("login") { inclusive = true }
            }
        }
    }

    // 🆕 Observar errores del AuthViewModel y mostrarlos con notificaciones
    LaunchedEffect(authViewModel.errorMessage) {
        authViewModel.errorMessage?.let { error ->
            notificationViewModel.showError(error)
            authViewModel.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(getBackgroundGradient())
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LanguageSelector(
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
        
        Spacer(modifier = Modifier.height(30.dp))

        // Logo sin caja - solo los íconos
        Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = stringResource(R.string.cd_location),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                imageVector = Icons.Default.AccessAlarm,
                contentDescription = stringResource(R.string.cd_alarm),
                tint = Color(0xFFFF6B6B),
                modifier = Modifier
                    .size(36.dp)
                    .offset(x = (-10).dp, y = 10.dp)
            )
        }

        Text(
            text = "RememberGo",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = stringResource(R.string.login_title),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Campo de correo electrónico
        AppTextField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(R.string.label_email),
            placeholder = stringResource(R.string.placeholder_email),
            leadingIcon = {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            enabled = !authViewModel.isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        AppTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.label_password),
            placeholder = stringResource(R.string.placeholder_password),
            leadingIcon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            isPassword = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            enabled = !authViewModel.isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (authViewModel.loginState is LoginState.Retrying) {
            val state = authViewModel.loginState as LoginState.Retrying
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "El servidor está iniciando, por favor espera...",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Intento ${state.attempt} de ${state.max}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            }
        } else {
            AppButton(
                text = if (authViewModel.isLoading) stringResource(R.string.logging_in) else stringResource(R.string.login_button),
                isLoading = authViewModel.isLoading,
                enabled = !authViewModel.isLoading,
                onClick = {
                    when {
                        email.isBlank() && password.isBlank() -> {
                            notificationViewModel.showError(context.getString(R.string.error_empty_fields))
                        }
                        email.isBlank() -> {
                            notificationViewModel.showError(context.getString(R.string.error_empty_email))
                        }
                        password.isBlank() -> {
                            notificationViewModel.showError(context.getString(R.string.error_empty_password))
                        }
                        else -> {
                            authViewModel.login(email, password) { loginExitoso ->
                                if (loginExitoso) {
                                    notificationViewModel.showSuccess(context.getString(R.string.login_success))
                                }
                            }
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        AppButton(
            text = stringResource(R.string.create_new_account),
            icon = Icons.Default.PersonAdd,
            outlined = true,
            enabled = !authViewModel.isLoading,
            onClick = { navController.navigate("register") },
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
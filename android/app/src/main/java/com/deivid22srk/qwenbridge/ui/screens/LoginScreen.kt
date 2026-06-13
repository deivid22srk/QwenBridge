package com.deivid22srk.qwenbridge.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deivid22srk.qwenbridge.ui.theme.BackgroundDark
import com.deivid22srk.qwenbridge.ui.theme.BorderLight
import com.deivid22srk.qwenbridge.ui.theme.PurplePrimary
import com.deivid22srk.qwenbridge.ui.theme.PurpleSecondary
import com.deivid22srk.qwenbridge.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onLoginSuccess: (email: String, cookies: String, userAgent: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var showWebView by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login Qwen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = BackgroundDark
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundDark)
        ) {
            if (!showWebView) {
                // Etapa 1: Digitar o e-mail da conta Qwen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Identifique sua Conta",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Digite o e-mail da sua conta do Qwen. Ele será usado para salvar suas credenciais localmente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-mail do Qwen") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PurplePrimary,
                            unfocusedBorderColor = TextSecondary,
                            focusedLabelColor = PurplePrimary,
                            unfocusedLabelColor = TextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            if (email.isNotBlank() && email.contains("@")) {
                                showWebView = true
                            }
                        },
                        enabled = email.isNotBlank() && email.contains("@"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PurplePrimary,
                            disabledContainerColor = PurplePrimary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Iniciar Login", color = Color.White)
                    }
                }
            } else {
                // Etapa 2: Exibir o WebView para autenticação interativa
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewInstance = this
                            
                            // Habilitar suporte completo a cookies (incluindo cookies de terceiros para login Google)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            
                            webChromeClient = WebChromeClient()
                            
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                
                                // Usar User Agent de Safari no iPhone para evitar o bloqueio de "disallowed_useragent" no Google Login
                                // e evitar a necessidade de headers "sec-ch-ua" (Client Hints) do Chromium que causam bloqueios na API do Qwen.
                                userAgentString = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    val currentUrl = url ?: ""
                                    // Se o redirecionamento pós-login ocorrer para a home ou chat
                                    if (currentUrl.contains("chat.qwen.ai") &&
                                        (!currentUrl.contains("/auth") && !currentUrl.contains("/login"))
                                    ) {
                                        val cookieManager = CookieManager.getInstance()
                                        val cookies = cookieManager.getCookie("https://chat.qwen.ai")
                                        if (!cookies.isNullOrBlank() &&
                                            (cookies.contains("session") || cookies.contains("token") || cookies.contains("trace_id"))
                                        ) {
                                            onLoginSuccess(
                                                email,
                                                cookies,
                                                settings.userAgentString
                                            )
                                        }
                                    }
                                }
                            }
                            loadUrl("https://chat.qwen.ai/auth")
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PurplePrimary)
                    }
                }
            }
        }
    }
}

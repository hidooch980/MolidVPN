package com.molidvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.molidvpn.ui.screens.LinkImportDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.molidvpn.core.VpnController
import com.molidvpn.core.VpnState
import com.molidvpn.core.VpnStatus
import com.molidvpn.core.wg.Warp
import com.molidvpn.core.wg.WgSelfTest
import com.molidvpn.ui.screens.SelfTestDialog
import com.molidvpn.ui.screens.WgConfigDialog
import com.molidvpn.data.Server
import com.molidvpn.data.Servers
import com.molidvpn.data.ProfileStore
import com.molidvpn.data.SettingsStore
import com.molidvpn.net.ConfigLink
import com.molidvpn.net.Smart
import com.molidvpn.ui.MolidRoot
import com.molidvpn.ui.theme.MolidTheme
import com.molidvpn.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val ctx = LocalContext.current
            val settings = remember { SettingsStore(ctx.applicationContext) }
            var selected by remember { mutableStateOf(Servers.byId(settings.selectedId)) }
            var toast by remember { mutableStateOf<String?>(null) }
            var showConfig by remember { mutableStateOf(false) }
            var showLink by remember { mutableStateOf(false) }
            val profiles = remember { ProfileStore(ctx.applicationContext) }
            var selfTestRunning by remember { mutableStateOf(false) }
            var selfTestResults by remember { mutableStateOf<List<WgSelfTest.Check>?>(null) }
            val scope = rememberCoroutineScope()
            val showToast: (String) -> Unit = { msg ->
                toast = msg
                scope.launch {
                    delay(2400)
                    if (toast == msg) toast = null
                }
            }

            val vpnLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    // The home screen already turns amber the instant start()
                    // runs; a toast saying "connecting" would outlive it.
                    VpnController.start(ctx)
                } else {
                    showToast("اجازهٔ اتصال داده نشد")
                }
            }

            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }

            val toggle: () -> Unit = {
                when (VpnState.status) {
                    VpnStatus.CONNECTED, VpnStatus.CONNECTING -> {
                        VpnController.stop(ctx)
                        showToast("اتصال قطع شد")
                    }
                    else -> {
                        // Psiphon runs from the bundled core and needs no WireGuard config.
                        if (settings.wgConfig.isBlank() && settings.mode != "psiphon") {
                            showConfig = true
                            showToast("اول پیکربندی تونل را وارد کن")
                        } else {
                            val permission = VpnController.prepare(ctx)
                            if (permission != null) vpnLauncher.launch(permission)
                            else {
                                VpnController.start(ctx)
                            }
                        }
                    }
                }
            }

            val select: (Server) -> Unit = { s ->
                selected = s
                settings.selectedId = s.id
                Smart.test(s)
            }

            LaunchedEffect(Unit) {
                Smart.test(selected)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            LaunchedEffect(Unit) {
                // Quiet on launch: only speak up when there is really something to install.
                if (settings.smartUpdate) Updater.check(ctx) { msg ->
                    if (!msg.contains("تازه")) showToast(msg)
                }
            }

            val runSelfTest: () -> Unit = {
                selfTestRunning = true
                selfTestResults = null
                scope.launch {
                    val res = withContext(Dispatchers.Default) {
                        try {
                            WgSelfTest.run()
                        } catch (e: Exception) {
                            listOf(WgSelfTest.Check("اجرای تست", false, e.message ?: e.toString()))
                        }
                    }
                    selfTestResults = res
                    selfTestRunning = false
                }
            }

            MolidTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MolidRoot(
                        settings = settings,
                        connected = VpnState.connected,
                        connecting = VpnState.handshaking,
                        failed = VpnState.status == VpnStatus.FAILED,
                        engineDetail = VpnState.detail,
            engine = VpnState.engine,
                        handshakeAge = VpnState.handshakeAge,
                        startedAt = VpnState.startedAt,
                        selected = selected,
                        ping = Smart.pings[selected.id],
                        rx = VpnState.rxMbps,
                        tx = VpnState.txMbps,
                        toast = toast,
                        onToast = showToast,
                        onToggle = toggle,
                        onSelect = select,
                        onCheckUpdate = { Updater.check(ctx, showToast) },
                        onEditConfig = { showConfig = true },
                        onSelfTest = { runSelfTest() },
                        onAddLink = { showLink = true },
                        profileCount = profiles.all.size,
                        onWarp = {
                            showToast("در حال دریافت پیکربندی رایگان…")
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching { Warp.profile() }.getOrNull()
                                }
                                if (text == null) {
                                    showToast("WARP در دسترس نیست؛ بعداً تلاش کن")
                                } else {
                                    settings.updateWgConfig(text)
                                    showToast("پیکربندی WARP ذخیره شد؛ وصل شو")
                                }
                            }
                        },
                        onOpenLink = { url ->
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { showToast("برای باز کردن لینک مرورگری پیدا نشد") }
                        },
                    )

                    if (showConfig) {
                        WgConfigDialog(
                            initial = settings.wgConfig,
                            onDismiss = { showConfig = false },
                            onSave = { text ->
                                settings.updateWgConfig(text)
                                showToast("پیکربندی تونل ذخیره شد")
                            },
                        )
                    }

                    if (showLink) {
                        LinkImportDialog(
                            onDismiss = { showLink = false },
                            onImport = { text ->
                                showLink = false
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { ConfigLink.read(text) }
                                    outcome.wireGuard?.let { settings.updateWgConfig(it) }
                                    profiles.add(outcome.profiles)
                                    showToast(outcome.message)
                                }
                            },
                        )
                    }

                    if (selfTestRunning || selfTestResults != null) {
                        SelfTestDialog(
                            running = selfTestRunning,
                            results = selfTestResults,
                            onRetry = { runSelfTest() },
                            onDismiss = {
                                selfTestResults = null
                                selfTestRunning = false
                            },
                        )
                    }
                }
            }
        }
    }
}

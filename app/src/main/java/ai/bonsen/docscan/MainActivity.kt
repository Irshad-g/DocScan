package ai.bonsen.docscan

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.bonsen.docscan.ui.CameraScreen
import ai.bonsen.docscan.ui.CropScreen
import ai.bonsen.docscan.ui.DocScanTheme
import ai.bonsen.docscan.ui.FolderPicker
import ai.bonsen.docscan.ui.HomeScreen
import ai.bonsen.docscan.ui.ReviewScreen
import ai.bonsen.docscan.ui.SignIn
import ai.bonsen.docscan.ui.SignInScreen
import ai.bonsen.docscan.scan.Cloud
import ai.bonsen.docscan.scan.DocDetector
import org.opencv.android.OpenCVLoader

sealed interface Screen {
    object Home : Screen
    object Camera : Screen
    object Review : Screen
    class Crop(val pageId: String) : Screen
    class Folders(val provider: Cloud.Provider) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engineOk = OpenCVLoader.initLocal() && runCatching { DocDetector.init(applicationContext) }.isSuccess
        setContent {
            DocScanTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (engineOk) {
                        App()
                    } else {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("The scanning engine could not start on this device.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun App(vm: ScanViewModel = viewModel()) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onGoogleResult(it.resultCode, it.data)
    }
    val signIn = SignIn(
        microsoft = { (ctx as? android.app.Activity)?.let { vm.signIn(it) } },
        google = { (ctx as? android.app.Activity)?.let { vm.signInGoogle(it) { req -> googleConsent.launch(req) } } }
    )

    // Microsoft sign-in takes work/school accounts from any organisation (msal_config.json: AzureADMultipleOrgs).
    // Sign-in is optional: it stays until it expires or is signed out, and "Skip" is remembered.
    if (!vm.accountChecked) return
    if (vm.account == null && !vm.signInSkipped) {
        screen = Screen.Home
        SignInScreen(signIn, onSkip = vm::skipSignIn)
        return
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm,
            signIn = signIn,
            onChooseFolder = { p -> screen = Screen.Folders(p) },
            onScan = { screen = Screen.Camera },
            onImported = { screen = Screen.Review }
        )

        Screen.Camera -> CameraScreen(
            vm = vm,
            onDone = { screen = if (vm.pages.isEmpty() && vm.pending == 0) Screen.Home else Screen.Review },
            onClose = { screen = if (vm.pages.isEmpty() && vm.pending == 0) Screen.Home else Screen.Review }
        )

        Screen.Review -> ReviewScreen(
            vm = vm,
            onAddPage = { screen = Screen.Camera },
            onCrop = { id -> screen = Screen.Crop(id) },
            onExit = { screen = Screen.Home }
        )

        is Screen.Folders -> FolderPicker(
            provider = s.provider,
            onChoose = { path -> vm.chooseFolder(path); screen = Screen.Home },
            onClose = { screen = Screen.Home }
        )

        is Screen.Crop -> CropScreen(
            vm = vm,
            pageId = s.pageId,
            onClose = { screen = Screen.Review }
        )
    }
}

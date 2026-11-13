package com.kscanner

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kscanner.data.ThemeMode
import com.kscanner.ui.DocumentViewModel
import com.kscanner.ui.EditorViewModel
import com.kscanner.ui.LibraryViewModel
import com.kscanner.ui.SettingsViewModel
import com.kscanner.ui.camera.CameraScreen
import com.kscanner.ui.document.DocumentScreen
import com.kscanner.ui.editor.EditorScreen
import com.kscanner.ui.library.LibraryScreen
import com.kscanner.ui.settings.SettingsScreen
import com.kscanner.ui.theme.KScannerTheme
import com.kscanner.ui.viewer.ViewerScreen

private object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    fun camera(documentId: Long?) = "camera?documentId=${documentId ?: -1L}"

    fun editor(path: String, documentId: Long?) =
        "editor/${Uri.encode(path)}?documentId=${documentId ?: -1L}"

    fun document(id: Long) = "document/$id"

    fun viewer(id: Long, page: Int) = "viewer/$id/$page"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as KScannerApp).container

        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            KScannerTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KScannerNavHost()
                }
            }
        }
    }
}

@Composable
private fun KScannerNavHost() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as KScannerApp
    val factory = remember(app) { AppViewModelFactory(app.container) }
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {

        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(factory = factory)
            LibraryScreen(
                viewModel = viewModel,
                onOpenDocument = { navController.navigate(Routes.document(it)) },
                onOpenCamera = { navController.navigate(Routes.camera(null)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            route = "camera?documentId={documentId}",
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val documentId = entry.arguments?.getLong("documentId")?.takeIf { it >= 0 }
            CameraScreen(
                documentId = documentId,
                onCaptured = { path ->
                    navController.navigate(Routes.editor(path, documentId))
                },
                onClose = { navController.popBackStack() },
            )
        }

        composable(
            route = "editor/{path}?documentId={documentId}",
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("documentId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val encoded = entry.arguments?.getString("path").orEmpty()
            val path = Uri.decode(encoded)
            val documentId = entry.arguments?.getLong("documentId")?.takeIf { it >= 0 }
            val viewModel: EditorViewModel = viewModel(factory = factory)

            EditorScreen(
                viewModel = viewModel,
                path = path,
                documentId = documentId,
                onSaved = { id ->
                    // Collapse the capture chain so Back from the document returns to the library.
                    navController.popBackStack(Routes.LIBRARY, inclusive = false)
                    navController.navigate(Routes.document(id))
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route = "document/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val viewModel: DocumentViewModel = viewModel(factory = factory)
            DocumentScreen(
                viewModel = viewModel,
                documentId = id,
                onBack = { navController.popBackStack() },
                onAddPage = { navController.navigate(Routes.camera(id)) },
                onOpenPage = { index -> navController.navigate(Routes.viewer(id, index)) },
                onDeleted = {
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = "viewer/{id}/{page}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val page = entry.arguments?.getInt("page") ?: 0
            val viewModel: DocumentViewModel = viewModel(factory = factory)
            ViewerScreen(
                viewModel = viewModel,
                documentId = id,
                startPage = page,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

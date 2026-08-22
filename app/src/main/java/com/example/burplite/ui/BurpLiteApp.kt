package com.example.burplite.ui

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.burplite.model.HttpTransaction

/**
 * Simple in-memory nav: history list -> detail -> repeater,
 * plus a full-screen intercept dialog that pops up whenever a
 * new paused transaction arrives (from ProxyViewModel.pending).
 */
@Composable
fun BurpLiteApp(viewModel: ProxyViewModel, caCertPath: String?) {
    val navController: NavHostController = rememberNavController()
    var selected by remember { mutableStateOf<HttpTransaction?>(null) }
    val pending by viewModel.pending.collectAsState()

    // Intercept editor takes over the screen whenever a request is paused
    if (pending != null) {
        InterceptEditorScreen(tx = pending!!, viewModel = viewModel, onDone = { })
        return
    }

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            TrafficListScreen(
                viewModel = viewModel,
                caCertPath = caCertPath,
                onOpenTransaction = { tx -> selected = tx; navController.navigate("detail") },
                onOpenRepeater = { tx -> selected = tx; navController.navigate("repeater") }
            )
        }
        composable("detail") {
            selected?.let { tx ->
                TransactionDetailScreen(tx = tx, onSendToRepeater = { navController.navigate("repeater") })
            }
        }
        composable("repeater") {
            RepeaterScreen(seed = selected, viewModel = viewModel)
        }
    }
}

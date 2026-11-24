package com.lucaslu.tickhub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucaslu.tickhub.ui.theme.TickHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TickHubTheme {
                TickHubApp()
            }
        }
    }
}

@Composable
private fun TickHubApp() {
    val vm: AppViewModel = viewModel()
    val loginState by vm.login.collectAsState()

    if (loginState.isLoggedIn) {
        ReposScreen(vm)
    } else {
        LoginScreen(state = loginState, vm = vm, configured = vm.clientConfigured())
    }
}

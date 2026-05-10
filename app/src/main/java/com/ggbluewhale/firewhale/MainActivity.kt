package ai.firewhale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.firewhale.ui.theme.FireWhaleAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FireWhaleAITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isEnabled = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isEnabled.value = isAccessibilityServiceEnabled(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "FireWhale AI Overlay",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Enable accessibility permission to show a floating scan circle. Tap the circle to scan visible text, links, and on supported devices a screenshot. The app then checks for misinformation, deceptive claims, phishing, and harmful websites."
        )
        Text(
            text = if (isEnabled.value) {
                "Accessibility status: Enabled"
            } else {
                "Accessibility status: Disabled"
            }
        )
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Open Accessibility Settings")
        }
        Button(
            onClick = { isEnabled.value = isAccessibilityServiceEnabled(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Refresh Status")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    FireWhaleAITheme {
        HomeScreen()
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent =
        ComponentName(context, ScreenScanAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expectedComponent, ignoreCase = true)
}
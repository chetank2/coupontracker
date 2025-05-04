import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class TestColorsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ColorTestScreen()
        }
    }
}

@Composable
fun ColorTestScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ColorSwatch("Primary (Black)", Color(0xFF000000))
        ColorSwatch("Primary Variant (Dark Gray)", Color(0xFF333333))
        ColorSwatch("Secondary (Medium Gray)", Color(0xFF666666))
        ColorSwatch("Secondary Variant (Darker Gray)", Color(0xFF444444))
        ColorSwatch("Accent (Light Gray)", Color(0xFF999999))
        ColorSwatch("Background (Light Gray)", Color(0xFFF5F5F5))
    }
}

@Composable
fun ColorSwatch(name: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = name)
    }
}

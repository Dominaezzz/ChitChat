import androidx.compose.desktop.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

fun main() {
	Window(title = "Compose for Desktop", size = IntSize(300, 300)) {
		var count by remember { mutableStateOf(0) }

		MaterialTheme {
			Column(Modifier.fillMaxSize(), Arrangement.spacedBy(5.dp)) {
				Button(modifier = Modifier.align(Alignment.CenterHorizontally),
					onClick = {
						count++
					}) {
					Text(if (count == 0) "Hello World" else "Clicked ${count}!")
				}
				Button(modifier = Modifier.align(Alignment.CenterHorizontally),
					onClick = {
						count = 0
					}) {
					Text("Reset")
				}
			}
		}
	}
}

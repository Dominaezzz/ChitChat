package me.dominaezzz.chitchat.util

import androidx.compose.foundation.layout.*
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun Banner(
	text: @Composable () -> Unit,
	confirmButton: @Composable () -> Unit,
	dismissButton: (@Composable () -> Unit)? = null,
	modifier: Modifier = Modifier,
	icon: (@Composable () -> Unit)? = null,
) {
	// FIXME: This only works for single line messages with one button on desktop.

	Row(
		modifier
			// .width(720.dp)
			.padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (icon != null) {
			Spacer(Modifier.width(16.dp))
			Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
				icon()
			}
		}

		Spacer(Modifier.width(24.dp))

		ProvideTextStyle(MaterialTheme.typography.body2) {
			text()
		}

		Spacer(
			Modifier
				.widthIn(min = 90.dp)
				.weight(weight = 1f)
		)

		if (dismissButton != null) {
			Box(Modifier.align(Alignment.Bottom)) {
				ProvideTextStyle(MaterialTheme.typography.button) {
					dismissButton()
				}
			}
		}
		Spacer(Modifier.width(8.dp))
		Box(Modifier.align(Alignment.Bottom)) {
			ProvideTextStyle(MaterialTheme.typography.button) {
				confirmButton()
			}
		}
		Spacer(Modifier.width(8.dp))
	}
}

@Composable
fun TooltipContent(text: String) {
	Card(
		modifier = Modifier.height(24.dp),
		backgroundColor = Color(0xFF232F34) // MaterialTheme.colors.onSurface
	) {
		Text(
			text = text,
			modifier = Modifier.padding(horizontal = 8.dp),
			color = MaterialTheme.colors.surface
		)
	}
}

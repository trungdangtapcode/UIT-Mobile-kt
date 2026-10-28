package vn.edu.uit.mobile.widgets

import android.appwidget.AppWidgetManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import vn.edu.uit.mobile.designsystem.TechnicalShapes

@Composable fun HomeWidgetsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val supported = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    HomeWidgetsContent(supported, onDismiss) { kind ->
        val component = widgetComponents(context).single { it.second == kind }.first
        val accepted = AppWidgetManager.getInstance(context).requestPinAppWidget(component, null, null)
        if (!accepted) {
            Toast.makeText(context, "Long-press your home screen and choose Widgets → UIT Mobile.", Toast.LENGTH_LONG).show()
        } else {
            onDismiss()
        }
    }
}

@Composable fun HomeWidgetsContent(pinSupported: Boolean, onDismiss: () -> Unit, onPin: (ScheduleWidgetKind) -> Unit) {
    var kind by remember { mutableStateOf(ScheduleWidgetKind.NEXT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TechnicalShapes.Dialog,
        modifier = Modifier.testTag("home-widget-picker"),
        title = { Text("Home screen widgets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("See your saved timetable without opening UIT Mobile.")
                ScheduleWidgetKind.entries.forEach { option ->
                    OutlinedButton(onClick = { kind = option }, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = kind == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (option == ScheduleWidgetKind.NEXT) "Next class · 4 × 2" else "Today · 4 × 3")
                            Text(if (option == ScheduleWidgetKind.NEXT) "Class, room and closest deadline" else "Remaining classes and closest deadline", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Widgets use your saved schedule and GMT+07. Android controls background update timing.", style = MaterialTheme.typography.bodySmall)
                Text("You can also long-press your home screen, choose Widgets, then UIT Mobile.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPin(kind) }, enabled = pinSupported, shape = TechnicalShapes.Control) { Text("Add to home screen") } },
        dismissButton = { TextButton(onClick = onDismiss, shape = TechnicalShapes.Control) { Text("Close") } }
    )
}

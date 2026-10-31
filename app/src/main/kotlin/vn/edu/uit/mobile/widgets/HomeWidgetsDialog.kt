package vn.edu.uit.mobile.widgets

import android.appwidget.AppWidgetManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        title = { Text(stringResource(R.string.wd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wd_body))
                ScheduleWidgetKind.entries.forEach { option ->
                    OutlinedButton(onClick = { kind = option }, shape = TechnicalShapes.Field, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = kind == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (option == ScheduleWidgetKind.NEXT) R.string.wd_next else R.string.wd_today))
                            Text(stringResource(if (option == ScheduleWidgetKind.NEXT) R.string.wd_next_desc else R.string.wd_today_desc), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(stringResource(R.string.wd_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.wd_manual), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPin(kind) }, enabled = pinSupported, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wd_add)) } },
        dismissButton = { TextButton(onClick = onDismiss, shape = TechnicalShapes.Control) { Text(stringResource(R.string.wd_close)) } }
    )
}

package vn.edu.uit.mobile.academic

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import vn.edu.uit.mobile.common.*
import vn.edu.uit.mobile.designsystem.*
import vn.edu.uit.mobile.model.*

private fun money(value: String?): String = value?.toBigDecimalOrNull()?.let { "${NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")).format(it)} ₫" } ?: "—"
private fun date(value: String): String = parsePortalDate(value)?.toString() ?: value.ifBlank { "Not provided" }

@Composable fun StudentServicesContent(section: Int, state: AcademicState, onRefresh: () -> Unit = {}, onAccounts: () -> Unit = {}) {
    var selectedTermId by rememberSaveable { mutableStateOf<String?>(null) }
    val services = state.snapshot.studentServices
    val title = when (section) {
        1 -> "Tuition"
        2 -> "Student procedures"
        else -> "Graduation"
    }
    val subtitle = when (section) {
        1 -> "Charges, deadlines and payment records"
        2 -> "Your requests and university responses"
        else -> "Assessment conditions and registration status"
    }
    LazyColumn(Modifier.fillMaxSize().testTag("student-services-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageHeading(title, subtitle) {
                IconButton(onClick = onRefresh, enabled = !state.servicesLoading && state.snapshot.portalLinked) { Icon(Icons.Outlined.Refresh, "Refresh student services") }
            }
        }
        if (!state.snapshot.portalLinked) {
            item {
                Panel {
                    SectionHeading("Connect UIT Portal")
                    Text("Sign in with UIT SSO to see your student services.")
                    Button(onClick = onAccounts, shape = TechnicalShapes.Field) { Text("Account settings") }
                }
            }
        } else {
            if (state.servicesLoading) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Updating student services…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.servicesError == AppError.SessionExpired) {
                item {
                    Panel {
                        Text("Your UIT Portal session has expired.", color = MaterialTheme.colorScheme.error)
                        Text("Sign in again in Account settings to update these records.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onAccounts, shape = TechnicalShapes.Control) { Text("Account settings") }
                    }
                }
            }
            if (state.servicesError != null && state.servicesError != AppError.SessionExpired && services?.issues.isNullOrEmpty()) {
                item { ServiceProblem(ServiceIssue("services", ServiceFailureKind.UNAVAILABLE), services != null, onRefresh) }
            }
            when (section) {
                1 -> {
                    item { ServiceProblem(services?.issues?.firstOrNull { it.key == "tuition" }, services?.tuition != null, onRefresh) }
                    val tuition = services?.tuition
                    if (tuition == null) {
                        item { NotLoaded(state.servicesLoading, "Tuition records have not been loaded.") }
                    } else {
                        item { Updated(tuition.fetchedAt) }
                        if (tuition.terms.isEmpty()) {
                            item { EmptyState("No tuition records", "UIT Portal returned an empty tuition list.") }
                        } else {
                            val selected = tuition.terms.firstOrNull { it.id == selectedTermId } ?: tuition.terms.first()
                            item {
                                var choosing by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth(), shape = TechnicalShapes.Field) {
                                        Text(selected.label.ifBlank { "Choose tuition term" }, Modifier.weight(1f))
                                        Icon(Icons.Outlined.ExpandMore, "Choose tuition term")
                                    }
                                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                        tuition.terms.forEach { term ->
                                            DropdownMenuItem(text = { Text(term.label.ifBlank { term.academicYear }) }, onClick = {
                                                selectedTermId = term.id
                                                choosing = false
                                            })
                                        }
                                    }
                                }
                            }
                            item {
                                Panel(Modifier.fillMaxWidth()) {
                                    Text("Remaining for this term", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(money(selected.remaining), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(selected.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            item(key = "tuition:${selected.id}") { TuitionTermPanel(selected) }
                        }
                    }
                    val extension = services?.procedures?.firstOrNull { it.kind == ProcedureKind.TUITION_EXTENSION }
                    item { ServiceProblem(services?.issues?.firstOrNull { it.key == ProcedureKind.TUITION_EXTENSION.name }, extension != null, onRefresh) }
                    extension?.let { item { ProcedurePanel(it) } }
                }
                2 -> {
                    items(ProcedureKind.entries.toList(), key = { it.name }) { kind ->
                        val data = services?.procedures?.firstOrNull { it.kind == kind }
                        ServiceProblem(services?.issues?.firstOrNull { it.key == kind.name }, data != null, onRefresh)
                        if (data == null) {
                            Panel {
                                SectionHeading(kind.title)
                                NotLoaded(state.servicesLoading, "This request history has not been loaded.")
                            }
                        } else {
                            ProcedurePanel(data)
                        }
                    }
                }
                else -> {
                    item { ServiceProblem(services?.issues?.firstOrNull { it.key == "graduation" }, services?.graduation != null, onRefresh) }
                    val graduation = services?.graduation
                    if (graduation == null) {
                        item { NotLoaded(state.servicesLoading, "Graduation assessment has not been loaded.") }
                    } else {
                        item { Updated(graduation.fetchedAt) }
                        item { GraduationOverview(graduation) }
                        item {
                            Panel {
                                SectionHeading("Assessment conditions", "Published by UIT Portal")
                                if (graduation.conditions.isEmpty()) Text("No assessment conditions were returned.")
                                graduation.conditions.forEach { condition ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(condition.name.ifBlank { "Assessment condition" }, style = MaterialTheme.typography.titleSmall)
                                        Text(condition.status.ifBlank { "Status not provided" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                        if (condition.note.isNotBlank()) Text(condition.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                        item {
                            Panel {
                                SectionHeading("Outstanding requirements")
                                if (graduation.debts.isEmpty()) Text("No requirement rows were returned.")
                                graduation.debts.forEach { debt ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(debt.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            debt.status.ifBlank {
                                                when (debt.satisfied) {
                                                    true -> "Satisfied"
                                                    false -> "Needs attention"
                                                    null -> "Not provided"
                                                }
                                            },
                                            color = if (debt.satisfied == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (debt.hint.isNotBlank()) Text(debt.hint, style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Updated(timestamp: Long) {
    if (timestamp > 0) Text("Saved ${dateText(timestamp)} at ${clockText(timestamp)} · GMT+07", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun NotLoaded(loading: Boolean, message: String) {
    if (!loading) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ServiceProblem(issue: ServiceIssue?, cached: Boolean, onRetry: () -> Unit) {
    if (issue == null) return
    Surface(shape = TechnicalShapes.Field, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (issue.failure) {
                    ServiceFailureKind.SIGN_IN -> "Sign in again to update this section."
                    ServiceFailureKind.NETWORK -> "Unable to connect to UIT Portal."
                    ServiceFailureKind.INVALID_RESPONSE -> "UIT Portal returned data this app could not read."
                    ServiceFailureKind.UNAVAILABLE -> "This student service is currently unavailable."
                },
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (cached) Text("Showing your previously saved records.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry, shape = TechnicalShapes.Control) { Text("Try again") }
        }
    }
}

@Composable private fun TuitionTermPanel(term: TuitionTerm) {
    var expanded by rememberSaveable(term.id) { mutableStateOf(false) }
    Panel(Modifier.animateContentSize()) {
        SectionHeading(term.label.ifBlank { "Tuition term" }, term.academicYear.takeIf(String::isNotBlank))
        if (term.status.isNotBlank()) Text(term.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ValueRow("Payable", money(term.payable))
        ValueRow("Paid", money(term.paid))
        ValueRow("Remaining", money(term.remaining), if ((term.remaining?.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        ValueRow("First deadline", date(term.dueDate))
        if (term.secondDueDate.isNotBlank()) ValueRow("Second deadline", date(term.secondDueDate))
        if (term.extensionDate.isNotBlank()) ValueRow("Extended until", date(term.extensionDate))
        TextButton(onClick = { expanded = !expanded }, shape = TechnicalShapes.Control) {
            Text(if (expanded) "Hide charge details" else "View charge details")
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            ValueRow("Prior debt", money(term.priorDebt))
            term.charges.forEach { charge ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(charge.courseName.ifBlank { charge.courseCode }, style = MaterialTheme.typography.titleSmall)
                Text(listOf(charge.courseCode, charge.credits?.let { "$it credits" }.orEmpty()).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(charge.amount), style = MaterialTheme.typography.titleMedium)
            }
            SectionHeading("Payment records")
            if (term.payments.isEmpty()) Text("No payment records were returned for this term.", style = MaterialTheme.typography.bodySmall)
            term.payments.forEach { payment ->
                ValueRow(money(payment.amount), date(payment.paidAt))
                Text(listOf(payment.bank, payment.status).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (term.note.isNotBlank()) Text(term.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ProcedurePanel(section: ProcedureSection) {
    var expanded by rememberSaveable(section.kind) { mutableStateOf(false) }
    Panel(Modifier.animateContentSize()) {
        SectionHeading(section.kind.title)
        Updated(section.fetchedAt)
        if (section.windowOpen != null) Text(if (section.windowOpen == true) "Application window open" else "Application window closed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        if (section.windowStart.isNotBlank()) ValueRow("Opens", date(section.windowStart))
        if (section.windowEnd.isNotBlank()) ValueRow("Closes", date(section.windowEnd))
        if (section.explanation.isNotBlank()) Text(section.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (section.records.isEmpty()) Text("No saved requests returned by UIT Portal.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        section.records.forEach { record ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(record.title, style = MaterialTheme.typography.titleSmall)
            if (record.reference.isNotBlank()) Text(record.reference, style = MaterialTheme.typography.bodySmall)
            Text(record.status.ifBlank { "Status not provided" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            if (record.date.isNotBlank()) Text(date(record.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodySmall)
        }
        if (section.options.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, shape = TechnicalShapes.Control) { Text(if (expanded) "Hide document types" else "Document types & fees") }
            if (expanded) {
                section.options.forEach { option ->
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(money(option.fee), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun GraduationOverview(data: GraduationSummary) {
    Panel {
        SectionHeading(data.period.ifBlank { "Graduation assessment" })
        Text(
            when (data.alreadyRegistered) {
                true -> "Registration recorded"
                false -> "No registration recorded"
                null -> "Registration status unavailable"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (data.registrationStatus.isNotBlank()) Text(data.registrationStatus)
        if (data.windowStatus.isNotBlank()) ValueRow("Assessment window", data.windowStatus)
        if (data.windowStart.isNotBlank()) ValueRow("Opens", date(data.windowStart))
        if (data.windowEnd.isNotBlank()) ValueRow("Closes", date(data.windowEnd))
        ValueRow(
            "Registration eligibility",
            when (data.canRegister) {
                true -> "Allowed by Portal"
                false -> "Not currently allowed"
                null -> "Not provided"
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        listOf("Documents" to data.documentGate, "Fees" to data.feeGate, "Credits" to data.creditGate, "Contact phone" to data.phoneGate).forEach { (label, satisfied) ->
            ValueRow(
                label,
                when (satisfied) {
                    true -> "Satisfied"
                    false -> "Needs attention"
                    null -> "Not provided"
                }
            )
        }
    }
}

@Composable private fun ValueRow(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

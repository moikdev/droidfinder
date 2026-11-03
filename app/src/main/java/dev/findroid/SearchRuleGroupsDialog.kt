package dev.findroid

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun String.rulePreview(limit: Int = 220): String =
    if (codePointCount(0, length) <= limit) this else substring(0, offsetByCodePoints(0, limit - 1)) + "…"
private data class EditingSearchCondition(val index: Int?, val value: SearchRuleNode?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchRuleGroupsDialog(
    initial: SearchRuleGroup?,
    tagNames: List<String>,
    contextValid: () -> Boolean,
    apply: (SearchRuleGroup?) -> Unit,
    dismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial?.draft() ?: SearchGroupDraft()) }
    var path by remember { mutableStateOf(emptyList<Int>()) }
    var editing by remember { mutableStateOf<EditingSearchCondition?>(null) }
    var fullSummary by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val group = draft.groupAt(path)
    val applied = remember(draft) { runCatching { draft.checkedDraft().applied() } }
    val scroll = remember(path) { ScrollState(0) }
    val staleMessage = "The current tab or search changed. Close Search criteria and try again."
    fun replace(value: SearchGroupDraft) {
        draft = draft.replaceGroup(path, value).checkedDraft()
        error = null
    }
    editing?.let { current ->
        SearchRuleConditionDialog(current.value, tagNames,
            save = { condition ->
                val children = group.children.toMutableList()
                if (current.index == null) children += SearchConditionDraft(condition)
                else children[current.index] = SearchConditionDraft(condition)
                runCatching { draft.replaceGroup(path, group.copy(children = children)).checkedDraft() }
                    .onSuccess { draft = it; editing = null; error = null }
                    .exceptionOrNull()?.message
            }, dismiss = { editing = null })
        return
    }
    WorkspaceUtility("Rule groups", "Combine rules with All, Any or None. Other search criteria and exclusions still apply.",
        "search-rule-groups", dismiss) { limit ->
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit)
            .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp).testTag("rule-group-scroll"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (path.isNotEmpty()) TextButton({ path = path.dropLast(1); error = null },
                Modifier.heightIn(min = 48.dp).testTag("rule-group-parent")) { Text("Back to parent group") }
            Text(if (path.isEmpty()) "Main group" else "Group " + path.joinToString(" / ") { (it + 1).toString() },
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.testTag("rule-group-path"))
            Text("Group level ${path.size + 1} of $SEARCH_GROUP_MAX_DEPTH", color = Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchRuleJoin.entries.forEach { join ->
                    FilterChip(group.join == join, { replace(group.copy(join = join)) }, label = { Text(join.title) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("rule-group-${join.name}"))
                }
            }
            Text(group.join.explanation, fontSize = 13.sp)
            if (group.children.isEmpty()) Text("No rules yet. Add a rule or a nested group.", color = Muted, fontSize = 13.sp)
            group.children.forEachIndexed { index, child ->
                Surface(color = AppBackground, modifier = Modifier.fillMaxWidth().testTag("rule-child-$index")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text((if (child is SearchGroupDraft) "Group" else "Rule") + " ${index + 1}", fontSize = 12.sp, color = Muted)
                        Text(child.draftSummary.rulePreview(), fontSize = 14.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({
                                if (child is SearchGroupDraft) { path = path + index; error = null }
                                else editing = EditingSearchCondition(index, (child as SearchConditionDraft).condition)
                            }, Modifier.heightIn(min = 48.dp).testTag("edit-rule-child-$index")
                                .semantics { contentDescription = "Edit ${if (child is SearchGroupDraft) "group" else "rule"} ${index + 1}" }) {
                                Text(if (child is SearchGroupDraft) "Edit group" else "Edit rule")
                            }
                            TextButton({ replace(group.copy(children = group.children.filterIndexed { i, _ -> i != index })) },
                                Modifier.heightIn(min = 48.dp).testTag("remove-rule-child-$index")
                                    .semantics { contentDescription = "Remove ${if (child is SearchGroupDraft) "group" else "rule"} ${index + 1}" }) {
                                Text(if (child is SearchGroupDraft) "Remove group" else "Remove rule")
                            }
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ editing = EditingSearchCondition(null, null) },
                    enabled = draft.nodeCount < SEARCH_GROUP_MAX_NODES,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("add-group-rule")) { Text("Add rule…") }
                TextButton({
                    val index = group.children.size
                    replace(group.copy(children = group.children + SearchGroupDraft()))
                    path = path + index
                }, enabled = draft.nodeCount < SEARCH_GROUP_MAX_NODES && path.size + 1 < SEARCH_GROUP_MAX_DEPTH,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("add-rule-group")) { Text("Add group") }
            }
            Text("${draft.nodeCount}/$SEARCH_GROUP_MAX_NODES rules and groups. Every group needs at least one rule before applying.",
                fontSize = 12.sp, color = Muted)
            HorizontalDivider()
            Text("Complete search group", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val summary = draft.draftSummary
            Text(if (fullSummary) summary else summary.rulePreview(), fontSize = 13.sp,
                modifier = Modifier.testTag("rule-group-summary"))
            if (summary.codePointCount(0, summary.length) > 220) TextButton({ fullSummary = !fullSummary }, Modifier.heightIn(min = 48.dp)) {
                Text(if (fullSummary) "Show shorter summary" else "Show full summary")
            }
            if (applied.isFailure) Text(applied.exceptionOrNull()?.message ?: "Complete every group before applying.", color = Muted, fontSize = 12.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("rule-group-error")) }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(dismiss, Modifier.heightIn(min = 48.dp).testTag("cancel-rule-groups")) { Text("Cancel") }
            if (initial != null) TextButton({ if (contextValid()) apply(null) else error = staleMessage },
                Modifier.heightIn(min = 48.dp).testTag("clear-rule-groups")) { Text("Remove rule groups") }
            Button({ if (contextValid()) apply(applied.getOrThrow()) else error = staleMessage },
                enabled = applied.isSuccess, modifier = Modifier.heightIn(min = 48.dp).testTag("apply-rule-groups")) { Text("Apply groups") }
        }
    }
}

private enum class SearchConditionType(val title: String) { NAME("Name"), KIND("Kind"), TAG("Tag"), MODIFIED("Modified"), SIZE("Size") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchRuleConditionDialog(
    initial: SearchRuleNode?,
    tagNames: List<String>,
    save: (SearchRuleNode) -> String?,
    dismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(when (initial) {
        is SearchKindRule -> SearchConditionType.KIND
        is SearchTagRule -> SearchConditionType.TAG
        is SearchModifiedRule -> SearchConditionType.MODIFIED
        is SearchSizeRule -> SearchConditionType.SIZE
        else -> SearchConditionType.NAME
    }) }
    var name by remember { mutableStateOf((initial as? SearchNameRule)?.value.orEmpty()) }
    var kind by remember { mutableStateOf((initial as? SearchKindRule)?.value ?: "Text document") }
    var tag by remember { mutableStateOf((initial as? SearchTagRule)?.value.orEmpty()) }
    var date by remember { mutableStateOf((initial as? SearchModifiedRule)?.date ?: SearchDate.TODAY) }
    var days by remember { mutableStateOf((initial as? SearchModifiedRule)?.days?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minimum by remember { mutableStateOf(searchKilobytes((initial as? SearchSizeRule)?.minimumBytes ?: 0)) }
    var maximum by remember { mutableStateOf(searchKilobytes((initial as? SearchSizeRule)?.maximumBytes ?: 0)) }
    var preset by remember { mutableStateOf(if (initial is SearchSizeRule)
        SearchSize.entries.firstOrNull { it != SearchSize.ANY && it.minimum == initial.minimumBytes && it.maximum == initial.maximumBytes } ?: SearchSize.ANY
        else SearchSize.UNDER_1_MB) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidate = runCatching {
        when (type) {
            SearchConditionType.NAME -> SearchNameRule(name)
            SearchConditionType.KIND -> SearchKindRule(kind)
            SearchConditionType.TAG -> SearchTagRule(tag)
            SearchConditionType.MODIFIED -> SearchModifiedRule(date,
                if (date != SearchDate.ANY) 0 else requireNotNull(days.toIntOrNull()?.takeIf { it > 0 }) { "Enter a positive whole number of days." })
            SearchConditionType.SIZE -> if (preset != SearchSize.ANY) SearchSizeRule(preset.minimum, preset.maximum) else
                SearchSizeRule(requireNotNull(searchBytes(minimum)) { "Enter minimum KB in nonnegative whole bytes." },
                    requireNotNull(searchBytes(maximum)) { "Enter maximum KB in nonnegative whole bytes." })
        }
    }
    WorkspaceUtility(if (initial == null) "Add rule" else "Edit rule", "Choose what this rule checks. Group choices determine how it combines with other rules.",
        "search-rule-condition", dismiss) { limit ->
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = limit).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag("rule-condition-scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchConditionType.entries.forEach { choice ->
                    FilterChip(type == choice, { type = choice; error = null }, label = { Text(choice.title) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("rule-condition-type-${choice.name}"))
                }
            }
            when (type) {
                SearchConditionType.NAME -> OutlinedTextField(name, { name = it; error = null }, label = { Text("Name contains") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rule-name-value"))
                SearchConditionType.KIND -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("Folder", "Image", "PDF document", "Text document", "Video", "Audio", "ZIP archive") + kind).distinct().forEach { value ->
                        FilterChip(kind == value, { kind = value; error = null }, label = { Text(value) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("rule-kind-$value"))
                    }
                }
                SearchConditionType.TAG -> {
                    OutlinedTextField(tag, { tag = it; error = null }, label = { Text("Exact tag name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rule-tag-value"))
                    Text("Tag names match exactly. Choose a saved tag or enter a name to match when that tag is assigned.", fontSize = 12.sp, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tagNames.distinct().forEach { value -> FilterChip(tag == value, { tag = value; error = null }, label = { Text(value) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("rule-tag-$value")) }
                    }
                }
                SearchConditionType.MODIFIED -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchDate.entries.filter { it != SearchDate.ANY }.forEach { value ->
                            FilterChip(date == value, { date = value; error = null }, label = { Text(value.title) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("rule-date-${value.name}"))
                        }
                        FilterChip(date == SearchDate.ANY, { date = SearchDate.ANY; error = null }, label = { Text("Past days") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("rule-date-days"))
                    }
                    if (date == SearchDate.ANY) OutlinedTextField(days, { days = it; error = null }, label = { Text("Number of past days") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rule-days-value"))
                    Text("Calendar choices follow your local date and first day of the week. Past days uses rolling 24-hour periods.", fontSize = 12.sp, color = Muted)
                }
                SearchConditionType.SIZE -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchSize.entries.forEach { value ->
                            FilterChip(preset == value, { preset = value; error = null }, label = { Text(if (value == SearchSize.ANY) "Custom limits" else value.title) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("rule-size-${value.name}"))
                        }
                    }
                    if (preset == SearchSize.ANY) {
                        OutlinedTextField(minimum, { minimum = it; error = null }, label = { Text("Minimum KB") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("rule-minimum-kb"))
                        OutlinedTextField(maximum, { maximum = it; error = null }, label = { Text("Maximum KB") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("rule-maximum-kb"))
                    }
                    Text("1 KB = 1,024 bytes. Size rules check files; an unknown size cannot satisfy a rule or its None group.", fontSize = 12.sp, color = Muted)
                }
            }
            candidate.exceptionOrNull()?.message?.let { Text(it, fontSize = 12.sp, color = Muted) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("rule-condition-error")) }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(dismiss, Modifier.heightIn(min = 48.dp).testTag("cancel-group-rule")) { Text("Cancel") }
            Button({ error = save(candidate.getOrThrow()) }, enabled = candidate.isSuccess,
                modifier = Modifier.heightIn(min = 48.dp).testTag("save-group-rule")) { Text(if (initial == null) "Add rule" else "Save rule") }
        }
    }
}

package com.lucaslu.tickhub.ui

import com.lucaslu.tickhub.util.t

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.ScanSnapshot
import com.lucaslu.tickhub.data.WorkflowItem
import com.lucaslu.tickhub.util.Haptics
import sh.calvin.reorderable.ReorderableItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: AppViewModel) {
    val context = LocalContext.current
    fun haptic() = Haptics.tick(context)
    val snapshot by vm.snapshot.collectAsState(initial = ScanSnapshot())
    val user by vm.loggedInUser.collectAsState(initial = null)
    val avatar by vm.avatarUrl.collectAsState(initial = null)
    val state by vm.repo.collectAsState()
    val refreshing by vm.refreshing.collectAsState()

    var multiSelect by remember { mutableStateOf(false) }
    var selectedRepoIds by remember { mutableStateOf(setOf<Long>()) }
    var expandedRepo by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmWf by remember { mutableStateOf<Pair<String, WorkflowItem>?>(null) }
    val orders by vm.orders.collectAsState()
    var repos by remember { mutableStateOf<List<com.lucaslu.tickhub.data.RepoItem>>(emptyList()) }
    val savedOrder = orders["repos"] ?: emptyList()
    // rebuild ordered list from data source + saved order; source-of-truth stays in VM cache
    LaunchedEffect(snapshot.repos, savedOrder) {
        repos = vm.applyOrder(snapshot.repos, savedOrder) { it.id.toString() }
    }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderState = sh.calvin.reorderable.rememberReorderableLazyListState(lazyListState) { from, to ->
        // header occupies index 0
        repos = repos.toMutableList().apply {
            add(to.index - 1, removeAt(from.index - 1))
        }
        haptic() // tick as the dragged card passes another card
        vm.saveOrder("repos", repos.map { it.id.toString() })
    }

    confirmWf?.let { (fullName, wf) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmWf = null },
            title = { Text(t("确认立即触发")) },
            text = { Text(t("将立即触发工作流「") + wf.name + t("」，确定继续？")) },
            confirmButton = {
                TextButton(onClick = {
                    haptic()
                    vm.triggerWorkflowNow(fullName, wf.id, wf.name)
                    confirmWf = null
                }) { Text(t("确认触发")) }
            },
            dismissButton = { TextButton(onClick = { haptic(); confirmWf = null }) { Text(t("取消")) } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        AvatarImage(url = avatar, name = user ?: "?")
                        Spacer(Modifier.padding(8.dp))
                        Text(user ?: t("未登录"), style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    if (multiSelect) {
                        TextButton(onClick = {
                            haptic()
                            selectedRepoIds = if (selectedRepoIds.size == repos.size) emptySet() else repos.map { it.id }.toSet()
                        }) { Text(t("全选")) }
                        TextButton(onClick = {
                            haptic()
                            selectedRepoIds.forEach { vm.removeRepo(it) }
                            selectedRepoIds = emptySet(); multiSelect = false
                        }) { Text(t("删除")) }
                        TextButton(onClick = { haptic(); multiSelect = false; selectedRepoIds = emptySet() }) { Text(t("取消")) }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { haptic(); vm.refreshProfile() }) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
                ) {
                    item {
                        Button(
                            onClick = { haptic(); vm.scanRepos() },
                            enabled = !state.scanning,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Text(if (state.scanning) t("扫描仓库与工作流") else t("扫描仓库与工作流"))
                            if (state.scanning) {
                                Spacer(Modifier.padding(4.dp))
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                        state.message?.let { Text(com.lucaslu.tickhub.util.L10n.tNow(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.error?.let { Text(com.lucaslu.tickhub.util.L10n.tNow(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        Text(
                            t("已纳管 ") + "${snapshot.workflows.count { it.selected }}" + t(" 个工作流 / ") + "${snapshot.repos.size}" + t(" 个仓库（下拉可刷新头像与纳管项）"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    items(repos, key = { it.id }) { repo ->
                        ReorderableItem(reorderState, key = repo.id) { isDragging ->
                            val wfs = snapshot.workflows.filter { it.repoId == repo.id && it.state == "active" }
                            SwipeableCard(
                                modifier = Modifier.fillMaxWidth(),
                                onDelete = { vm.removeRepo(repo.id) },
                                onClick = {
                                    if (!multiSelect) {
                                        expandedRepo = if (expandedRepo == repo.id) null else repo.id
                                        haptic()
                                    } else {
                                        selectedRepoIds = if (selectedRepoIds.contains(repo.id)) selectedRepoIds - repo.id else selectedRepoIds + repo.id
                                    }
                                },
                                onSelect = { sel ->
                                    multiSelect = true
                                    selectedRepoIds = if (sel) selectedRepoIds + repo.id else selectedRepoIds - repo.id
                                },
                                selected = selectedRepoIds.contains(repo.id),
                                autoSelected = multiSelect && selectedRepoIds.contains(repo.id),
                                dragging = isDragging,
                                dragHandle = with(this) {
                                    Modifier.longPressDraggableHandle(
                                        onDragStarted = { haptic() },
                                        onDragStopped = { haptic() }
                                    )
                                },
                                content = {
                                    RepoCardContent(
                                        repoName = repo.name,
                                        private = repo.private,
                                        managed = repo.managed,
                                        workflows = wfs,
                                        expanded = expandedRepo == repo.id,
                                        selectedIds = wfs.filter { it.selected }.map { it.id }.toSet(),
                                        onManagedChange = { vm.toggleManaged(repo.id, it) },
                                        onWorkflowToggle = { id, checked -> vm.toggleWorkflow(id, checked) },
                                        onTriggerWorkflow = { wf -> confirmWf = repo.fullName to wf }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCardContent(
    repoName: String,
    private: Boolean,
    managed: Boolean,
    workflows: List<WorkflowItem>,
    expanded: Boolean,
    selectedIds: Set<Long>,
    onManagedChange: (Boolean) -> Unit,
    onWorkflowToggle: (Long, Boolean) -> Unit,
    onTriggerWorkflow: (WorkflowItem) -> Unit
) {
    val ctx = LocalContext.current
    fun cardHaptic() = Haptics.tick(ctx)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(repoName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${if (private) t("私有") else t("公开")} · ${selectedIds.size}/${workflows.size}" + t(" 工作流"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(12.dp))
            Text(t("全选"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(4.dp))
            MiuixSwitch(checked = managed, onCheckedChange = { cardHaptic(); onManagedChange(it) })
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            workflows.forEach { wf ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Checkbox(
                        checked = selectedIds.contains(wf.id),
                        onCheckedChange = { onWorkflowToggle(wf.id, it); cardHaptic() }
                    )
                    Text(wf.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onTriggerWorkflow(wf); cardHaptic() }) { Text(t("立即触发")) }
                }
            }
        }
    }
}

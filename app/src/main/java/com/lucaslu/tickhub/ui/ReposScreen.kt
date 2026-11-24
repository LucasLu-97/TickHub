package com.lucaslu.tickhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.ScanSnapshot
import com.lucaslu.tickhub.data.WorkflowItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: AppViewModel) {
    val snapshot by vm.snapshot.collectAsState(initial = ScanSnapshot())
    val user by vm.loggedInUser.collectAsState(initial = null)
    val state by vm.repo.collectAsState()

    var multiSelect by remember { mutableStateOf(false) }
    var selectedRepoIds by remember { mutableStateOf(setOf<Long>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (user ?: "?").take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(Modifier.padding(8.dp))
                        Text(user ?: "未登录", style = MaterialTheme.typography.titleMedium)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Button(
                onClick = { vm.scanRepos() },
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(4.dp))
                }
                Text(if (state.scanning) "扫描中…" else "扫描仓库与工作流")
            }

            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    "已纳管 ${snapshot.workflows.count { it.selected }} 个工作流 / ${snapshot.repos.size} 个仓库",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (multiSelect) {
                    TextButton(onClick = {
                        selectedRepoIds = if (selectedRepoIds.size == snapshot.repos.size) emptySet() else snapshot.repos.map { it.id }.toSet()
                    }) { Text("全选") }
                    TextButton(onClick = {
                        selectedRepoIds.forEach { vm.removeRepo(it) }
                        selectedRepoIds = emptySet(); multiSelect = false
                    }) { Text("删除") }
                    TextButton(onClick = { multiSelect = false; selectedRepoIds = emptySet() }) { Text("取消") }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(snapshot.repos, key = { it.id }) { repo ->
                    val wfs = snapshot.workflows.filter { it.repoId == repo.id && it.state == "active" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (multiSelect) {
                            Checkbox(
                                checked = selectedRepoIds.contains(repo.id),
                                onCheckedChange = {
                                    selectedRepoIds = if (it) selectedRepoIds + repo.id else selectedRepoIds - repo.id
                                }
                            )
                        }
                        SwipeableCard(
                            onDelete = { vm.removeRepo(repo.id) },
                            onLongClick = {
                                multiSelect = true
                                selectedRepoIds = selectedRepoIds + repo.id
                            },
                            content = {
                                RepoCardContent(
                                    repoName = repo.name,
                                    private = repo.private,
                                    managed = repo.managed,
                                    workflows = wfs,
                                    selectedIds = wfs.filter { it.selected }.map { it.id }.toSet(),
                                    onManagedChange = { vm.toggleManaged(repo.id, it) },
                                    onWorkflowToggle = { id, checked -> vm.toggleWorkflow(id, checked) },
                                    onTriggerWorkflow = { wf -> vm.triggerWorkflowNow(repo.fullName, wf.id, wf.name) }
                                )
                            }
                        )
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
    selectedIds: Set<Long>,
    onManagedChange: (Boolean) -> Unit,
    onWorkflowToggle: (Long, Boolean) -> Unit,
    onTriggerWorkflow: (WorkflowItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(repoName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${if (private) "私有" else "公开"} · ${workflows.size} 个工作流",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.padding(12.dp))
                Text("全选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                Switch(checked = managed, onCheckedChange = onManagedChange)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                workflows.forEach { wf ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Checkbox(
                            checked = selectedIds.contains(wf.id),
                            onCheckedChange = { onWorkflowToggle(wf.id, it) }
                        )
                        Text(wf.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onTriggerWorkflow(wf) }) { Text("立即触发") }
                    }
                }
            }
        }
    }
}

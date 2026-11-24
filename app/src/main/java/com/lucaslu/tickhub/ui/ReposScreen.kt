package com.lucaslu.tickhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucaslu.tickhub.data.ScanSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: AppViewModel) {
    val snapshot by vm.snapshot.collectAsState(initial = ScanSnapshot())
    val user by vm.loggedInUser.collectAsState(initial = null)
    val state by vm.repo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TickHub · ${user ?: "仓库"}") },
                actions = {
                    Text(
                        "退出",
                        modifier = Modifier.padding(12.dp).clickable { vm.logout() },
                        color = MaterialTheme.colorScheme.primary
                    )
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

            val managedCount = snapshot.workflows.count { it.selected }
            Text(
                "已纳管 $managedCount 个工作流 / ${snapshot.repos.size} 个仓库",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(snapshot.repos, key = { it.id }) { repo ->
                    val wfs = snapshot.workflows.filter { it.repoId == repo.id && it.state == "active" }
                    RepoCard(
                        fullName = repo.fullName,
                        private = repo.private,
                        managed = repo.managed,
                        workflows = wfs.map { it.id to it.name },
                        selectedIds = wfs.filter { it.selected }.map { it.id }.toSet(),
                        onManagedChange = { vm.toggleManaged(repo.id, it) },
                        onWorkflowToggle = { id, checked -> vm.toggleWorkflow(id, checked) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RepoCard(
    fullName: String,
    private: Boolean,
    managed: Boolean,
    workflows: List<Pair<Long, String>>,
    selectedIds: Set<Long>,
    onManagedChange: (Boolean) -> Unit,
    onWorkflowToggle: (Long, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(fullName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${if (private) "私有" else "公开"} · ${workflows.size} 个工作流",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("全选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = managed, onCheckedChange = onManagedChange)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                workflows.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Checkbox(
                            checked = selectedIds.contains(id),
                            onCheckedChange = { onWorkflowToggle(id, it) }
                        )
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

package dev.ohmysync.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.ohmysync.MainViewModel
import dev.ohmysync.BuildConfig
import dev.ohmysync.RunningSync
import dev.ohmysync.data.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.DateFormat
import java.util.Date

private val Accent = Color(0xFF3976E8)

@Composable
fun SyncApp(model: MainViewModel) {
    val dark = isSystemInDarkTheme()
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        val state by model.state.collectAsStateWithLifecycle()
        val account by model.account.collectAsStateWithLifecycle()
        val running by model.running.collectAsStateWithLifecycle()
        val work by model.work.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val needsReauth by model.needsReauth.collectAsStateWithLifecycle()
        val cloudFolder by model.cloudFolder.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var page by rememberSaveable { mutableStateOf("任务") }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyId by rememberSaveable { mutableStateOf<String?>(null) }
        val editing = editingId?.let { id -> state.tasks.find { it.id == id } }
        fun addTask() {
            if (account == null) { page = "账户"; model.notify("连接 OneDrive 后即可添加任务") }
            else {
                editingId = null
                page = "编辑"
            }
        }
        BackHandler(page == "编辑") { page = "任务" }
        BackHandler(page == "历史") { page = "任务" }
        StartupAccess(model)
        Scaffold(
            bottomBar = {
                if (page != "编辑") NavigationBar(showDivider = false) {
                    listOf("任务", "记录", "账户").forEach { item ->
                        NavigationBarItem(selected = page == item || (page == "历史" && item == "任务"), onClick = { page = item },
                            icon = navIcon(item), label = item)
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                cloudFolder?.let { folder ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("云端文件", fontWeight = FontWeight.Bold)
                            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                Muted(if (folder.entries.isEmpty()) "此目录暂时没有文件" else
                                    folder.entries.take(8).joinToString("\n") { "${it.name} · ${if (it.isFolder) "文件夹" else formatBytes(it.size)}" })
                            }
                            if (folder.hasMore || folder.entries.size > 8) Muted("更多内容请打开云端目录查看")
                            Row {
                                TextButton(text = "打开云端目录", onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, folder.webUrl.toUri()))
                                })
                                TextButton(text = "关闭", onClick = { model.cloudFolder.value = null })
                            }
                        }
                    }
                }
                if (message != null) Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message!!, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(text = "知道了", onClick = { model.message.value = null })
                    }
                }
                when (page) {
                    "编辑" -> TaskEditor(editing, account, onSave = { model.save(it) { page = "任务" } },
                        onBack = { page = "任务" }, onError = model::notify)
                    "账户" -> AccountPage(account, busy, needsReauth, model::connect, model::disconnect)
                    "记录" -> HistoryPage(state.records)
                    "历史" -> {
                        val task = state.tasks.find { it.id == historyId }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { PageHeading("曲库历史", "恢复为副本，保留当前文件") }
                            item { TextButton(text = "返回任务", onClick = { page = "任务" }) }
                            val pending = state.operations.filter { it.taskId == historyId }
                            if (pending.isNotEmpty()) item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${pending.size} 个未完成操作", fontWeight = FontWeight.Bold)
                                        Muted(pending.joinToString("\n") { it.path })
                                        Muted("下一次同步会先核对原文件、临时文件与保留副本。遇到新的外部修改会停止，保留全部版本。")
                                        if (task != null) TextButton(text = "继续同步并恢复", onClick = { model.sync(task) })
                                    }
                                }
                            }
                            val events = state.libraryEvents.filter { it.taskId == historyId }
                            val deletes = task?.let { task -> state.deleteCandidates.filter {
                                it.key.startsWith("${task.driveId}/${task.id}/${task.remotePath}/")
                            } }.orEmpty()
                            if (deletes.isNotEmpty()) item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${deletes.size} 个删除候选", fontWeight = FontWeight.Bold)
                                        Muted(if (task?.syncDeletes == true) "下一次完整扫描会再次核对，间隔至少一分钟。大批缺失会停止传播。" else "当前仅提示。可在任务设置中单独确认启用删除传播。")
                                        deletes.forEach { candidate ->
                                            val prefix = "${task?.driveId}/${task?.id}/${task?.remotePath}/"
                                            Muted("${candidate.key.removePrefix(prefix)}\n${if (candidate.direction == "DeleteLocal") "云端缺失 → 待隔离手机版" else "手机缺失 → 待移入云端历史"}")
                                        }
                                    }
                                }
                            }
                            if (events.isNotEmpty()) item {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("最近曲库操作", fontWeight = FontWeight.Bold)
                                    events.take(10).forEach { Muted("${formatTime(it.time)} · ${it.path}\n${it.message}") }
                                }
                            }
                            val versions = state.versions.filter { it.taskId == historyId }
                            if (versions.isEmpty()) item { Muted("曲库第一次同步后会在这里保存版本。") }
                            items(versions) { version ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(version.path, fontWeight = FontWeight.Bold)
                                        Muted("${formatTime(version.time)} · ${version.source} · ${formatBytes(version.size)}")
                                        if (task != null) TextButton(text = "恢复为副本", onClick = { model.restoreVersion(task, version) })
                                    }
                                }
                            }
                        }
                    }
                    else -> TasksPage(state, account, running, work, ::addTask,
                        onEdit = { editingId = it.id; page = "编辑" }, onSync = model::sync, onToggle = model::toggle, onStop = model::stop,
                        onCloud = model::inspectCloud, onHistory = { historyId = it.id; page = "历史" })
                }
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Muted(subtitle)
        }
        action?.invoke()
    }
}

@Composable
private fun TasksPage(state: SyncState, account: Account?, running: RunningSync?, work: List<WorkInfo>,
                      onAdd: () -> Unit, onEdit: (SyncTask) -> Unit, onSync: (SyncTask) -> Unit,
                      onToggle: (SyncTask) -> Unit, onStop: (SyncTask) -> Unit, onCloud: (SyncTask) -> Unit,
                      onHistory: (SyncTask) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("轻同步", "让曲库与文件保持同步", action = {
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColorsPrimary()) { Text("＋ 新建") }
        }) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(Accent.copy(alpha = .07f)).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon("sync", Accent, Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (running != null) "正在同步" else "同步概览", modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Badge(if (account != null) "OneDrive 已连接" else "未连接")
                    }
                    Spacer(Modifier.height(8.dp))
                    Muted(if (running != null) running.file else if (account == null) "登录 OneDrive，连接手机与电脑的文件。" else "自动同步 · 版本可追溯")
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("${state.tasks.count { it.enabled }}", "启用任务", Modifier.weight(1f))
                        Stat("${state.records.count { it.status == "success" }}", "近期成功", Modifier.weight(1f))
                    }
                }
            }
        }
        item { SectionLabel("文件夹任务", "${state.tasks.size} 个") }
        if (state.tasks.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon("folder", Accent, Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("连接你的第一个文件夹", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Muted("曲库双向同步，或将文件备份到云端。")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onAdd, colors = ButtonDefaults.buttonColorsPrimary()) { Text("创建第一个任务") }
                }
            }
        }
        items(state.tasks, key = { it.id }) { task ->
            val active = running?.takeIf { it.taskId == task.id }
            val queued = work.any { task.id in it.tags && "manual" in it.tags && it.state == WorkInfo.State.ENQUEUED }
            TaskCard(task, active, queued, state.records.firstOrNull { it.taskId == task.id },
                { onEdit(task) }, { onSync(task) }, { onToggle(task) }, { onStop(task) }, { onCloud(task) }, { onHistory(task) })
        }
        item { Muted("定时同步由 Android 调度，实际启动时间可能延后。首次使用建议先同步一个小文件夹。") }
    }
}

@Composable
private fun TaskCard(task: SyncTask, active: RunningSync?, queued: Boolean, record: SyncRecord?,
                     onEdit: () -> Unit, onSync: () -> Unit, onToggle: () -> Unit, onStop: () -> Unit, onCloud: () -> Unit,
                     onHistory: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Accent.copy(alpha = .1f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    AppIcon("folder", Accent, Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Muted(if (!task.enabled) "已暂停" else if (active != null) "正在同步" else if (queued) "等待同步条件" else "每 ${task.intervalMinutes} 分钟同步")
                }
                TextButton(text = "编辑", onClick = onEdit, enabled = active == null)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Muted("手机目录")
                    Text(task.localName, fontSize = 14.sp, maxLines = 2)
                }
                Text(if (task.twoWay) " ↔ " else " → ", color = Accent, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Muted("OneDrive")
                    Text("/${task.remotePath}", fontSize = 14.sp, maxLines = 2)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(if (task.wifiOnly) "仅 Wi-Fi" else "所有网络")
                if (task.chargingOnly) Badge("充电时")
                Badge(if (task.twoWay) "双向曲库 · 保留历史" else if (task.overwrite) "覆盖同名文件" else "保留云端冲突")
                if (task.twoWay) Badge(if (task.syncDeletes) "删除传播已开启" else "删除仅提示")
            }
            if (active != null) {
                Muted(active.file)
                val fraction = if (active.total > 0) active.done.toFloat() / active.total else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).background(Accent.copy(alpha = .12f), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Accent, RoundedCornerShape(3.dp)))
                }
                if (active.total > 0) Muted("${formatBytes(active.done)} / ${formatBytes(active.total)}")
            } else if (record != null) {
                Muted("${if (record.status == "success") "上次完成" else "上次结果"} · ${formatTime(record.time)}\n${record.message}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (active != null || queued) onStop else onSync, modifier = Modifier.weight(1f),
                    enabled = task.enabled, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (active != null || queued) "停止本次同步" else "立即同步")
                }
                Button(onClick = onToggle) { Text(if (task.enabled) "暂停" else "启用") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "云端文件", onClick = onCloud)
                if (task.twoWay) TextButton(text = "版本历史", onClick = onHistory)
            }
        }
    }
}

@Composable
private fun TaskEditor(existing: SyncTask?, account: Account?, onSave: (SyncTask) -> Unit,
                       onBack: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var uri by rememberSaveable(existing?.id) { mutableStateOf(existing?.treeUri ?: "") }
    var localName by rememberSaveable(existing?.id) { mutableStateOf(existing?.localName ?: "") }
    var remote by rememberSaveable(existing?.id) { mutableStateOf(existing?.remotePath ?: "轻同步") }
    var interval by rememberSaveable(existing?.id) { mutableLongStateOf(existing?.intervalMinutes ?: 60L) }
    var wifi by rememberSaveable(existing?.id) { mutableStateOf(existing?.wifiOnly ?: true) }
    var charging by rememberSaveable(existing?.id) { mutableStateOf(existing?.chargingOnly ?: false) }
    var overwrite by rememberSaveable(existing?.id) { mutableStateOf(existing?.overwrite ?: false) }
    var twoWay by rememberSaveable(existing?.id) { mutableStateOf(existing?.twoWay ?: true) }
    var syncDeletes by rememberSaveable(existing?.id) { mutableStateOf(existing?.syncDeletes ?: false) }
    var confirmDeletes by rememberSaveable(existing?.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selected ->
        if (selected != null) try {
            context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (twoWay) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            val folder = DocumentFile.fromTreeUri(context, selected)
            check(folder?.isDirectory == true && folder.canRead()) { "无法读取这个文件夹，请选择其他位置" }
            uri = selected.toString()
            localName = folder.name ?: "本地文件夹"
            if (name.isBlank()) name = localName
            if (existing == null) remote = "轻同步/$localName"
        } catch (error: Exception) { onError(error.message ?: "文件夹授权失败，请重试") }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading(if (existing == null) "新建任务" else "编辑任务", if (twoWay) "手机曲库 ↔ OneDrive ↔ 电脑" else "手机文件夹 → OneDrive") }
        item { ToggleRow("双向曲库同步 · 预览", "同步歌曲、标签、封面与歌词，修改前保存历史", twoWay) { twoWay = it } }
        item { TextField(value = name, onValueChange = { name = it }, label = "任务名称", modifier = Modifier.fillMaxWidth()) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("本地文件夹", if (twoWay) "需要读写授权" else "系统文件夹授权")
                    Text(localName.ifBlank { "尚未选择文件夹" }, fontWeight = FontWeight.Medium)
                    Button(onClick = { picker.launch(uri.takeIf { it.isNotBlank() }?.let(Uri::parse)) }, modifier = Modifier.fillMaxWidth()) { Text("选择手机文件夹") }
                    Muted("系统会限制部分根目录和 Android/data 等位置的访问。")
                }
            }
        }
        item { TextField(value = remote, onValueChange = { remote = it }, label = "OneDrive 文件夹路径", modifier = Modifier.fillMaxWidth()) }
        item { Muted("例如：手机备份/照片。路径相对于 OneDrive 根目录，不存在的文件夹会自动创建。") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("自动同步", "系统调度")
                    listOf(15L to "每 15 分钟", 60L to "每小时", 360L to "每 6 小时", 1440L to "每天").chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (value, label) -> Button(onClick = { interval = value }, modifier = Modifier.weight(1f),
                                colors = if (interval == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(label) } }
                        }
                    }
                    ToggleRow("仅 Wi-Fi 同步", "使用系统认定的不计费网络", wifi) { wifi = it }
                    ToggleRow("仅充电时同步", "适合较大的备份任务", charging) { charging = it }
                    if (!twoWay) ToggleRow("允许覆盖云端同名文件", "开启后，手机内容会替换云端同名文件", overwrite) { overwrite = it }
                    if (twoWay) {
                        ToggleRow("同步删除", "一端删歌后，另一端未修改的歌曲会移出有效曲库", syncDeletes) {
                            if (it) confirmDeletes = true else { syncDeletes = false; confirmDeletes = false }
                        }
                        if (confirmDeletes) {
                            Muted("启用后会自动传播删除：先保存历史，手机原文件改名隔离，云端原文件移入历史目录，不永久清除。缺失需两次完整扫描确认；删除与修改冲突、大批缺失会停止。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { confirmDeletes = false }) { Text("暂不开启") }
                                Button(onClick = { syncDeletes = true; confirmDeletes = false }) { Text("确认启用删除传播") }
                            }
                        }
                    }
                }
            }
        }
        item { Muted(if (twoWay) "单端新增和修改自动传播；两端同时修改时保留冲突。历史保存在 OneDrive 的“轻同步历史”中，会占用空间。下载和恢复使用校验后的临时文件与持久操作记录，旧版保留为隐藏改名副本；历史页可查看操作并恢复为副本。删除默认只提示，需单独启用。旧任务切换曲库模式时，请重新选择手机文件夹授予写入权限。"
            else "未开启覆盖时，只更新本任务此前上传且云端未被修改的文件；其他同名文件会保留并记录冲突。手机与云端文件均不会自动删除。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onBack) { Text("返回") }
                Button(onClick = {
                    if (uri.isBlank()) onError("请先选择本地文件夹")
                    else if (account == null) onError("请先连接 OneDrive")
                    else onSave(SyncTask(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(),
                        treeUri = uri, localName = localName, remotePath = remote.trim().trim('/'), driveId = existing?.driveId ?: account.driveId,
                        intervalMinutes = interval, wifiOnly = wifi, chargingOnly = charging, enabled = existing?.enabled ?: true,
                        overwrite = if (twoWay) false else overwrite, twoWay = twoWay, syncDeletes = twoWay && syncDeletes))
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存任务") }
            }
        }
    }
}

@Composable
private fun AccountPage(account: Account?, busy: Boolean, needsReauth: Boolean,
                        onConnect: (android.content.Context) -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("云端账户", "连接你的文件目的地") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon("cloud", Accent, Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("OneDrive", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Muted(if (busy) "正在完成授权…" else if (needsReauth) "登录已失效，请重新授权" else if (account != null) "已连接 · ${account.name}" else "微软个人、工作或学校账户")
                        }
                    }
                    if (account != null) {
                        Muted("连接时剩余空间 · ${formatBytes(account.remaining)}")
                        Button(onClick = { onConnect(context) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text("重新授权") }
                        Button(onClick = { confirm = !confirm }, modifier = Modifier.fillMaxWidth()) { Text("断开账户") }
                        if (confirm) {
                            Muted("断开后会停止当前同步并暂停所有任务。")
                            Button(onClick = { onDisconnect(); confirm = false }, modifier = Modifier.fillMaxWidth()) { Text("确认断开") }
                        }
                    } else {
                        Button(onClick = { onConnect(context) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (busy) "正在连接…" else "连接 OneDrive") }
                        Muted("通过系统浏览器登录；应用不会读取或保存你的微软密码。")
                    }
                }
            }
        }
        item { Muted("轻同步 · ${BuildConfig.VERSION_NAME} 预览版\n支持单向备份与双向曲库预览。") }
    }
}

@Composable
private fun HistoryPage(records: List<SyncRecord>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("同步记录", "每一次同步，都有迹可循") }
        if (records.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("还没有同步记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Muted("运行一个任务后，这里会显示上传结果和需要处理的问题。")
                }
            }
        }
        items(records) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(record.taskName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Badge(when (record.status) { "success" -> "已完成"; "conflict" -> "存在冲突"; "cancelled" -> "已停止"; else -> "需处理" })
                    }
                    Muted(formatTime(record.time))
                    Text(record.message, fontSize = 15.sp)
                    Muted("上传 ${record.uploaded} 个 · ${formatBytes(record.bytes)}")
                }
            }
        }
        if (records.isNotEmpty()) item { Muted("保留最近 100 条记录") }
    }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp)
            Muted(summary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Muted(text: String) = Text(text, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .58f), fontSize = 13.sp, lineHeight = 20.sp)

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Muted(subtitle)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.height(3.dp))
        Muted(label)
    }
}

@Composable
private fun Badge(label: String) {
    Text(label, fontSize = 11.sp, color = Accent,
        modifier = Modifier.background(Accent.copy(alpha = .08f), RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun AppIcon(kind: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.8f * unit)
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (kind) {
            "folder" -> {
                val path = Path().apply {
                    moveTo(3 * unit, 6 * unit); lineTo(9 * unit, 6 * unit); lineTo(11 * unit, 9 * unit)
                    lineTo(21 * unit, 9 * unit); lineTo(21 * unit, 20 * unit); lineTo(3 * unit, 20 * unit); close()
                }
                drawPath(path, color, style = stroke)
            }
            "cloud" -> {
                val path = Path().apply {
                    moveTo(6 * unit, 19 * unit)
                    cubicTo(0f, 19 * unit, 0f, 10 * unit, 6 * unit, 10 * unit)
                    cubicTo(6 * unit, 2 * unit, 18 * unit, 2 * unit, 19 * unit, 11 * unit)
                    cubicTo(25 * unit, 11 * unit, 25 * unit, 19 * unit, 19 * unit, 19 * unit); close()
                }
                drawPath(path, color, style = stroke)
            }
            else -> {
                drawArc(color, 205f, 145f, false, p(4f, 4f), Size(16 * unit, 16 * unit), style = stroke)
                drawArc(color, 25f, 145f, false, p(4f, 4f), Size(16 * unit, 16 * unit), style = stroke)
                drawLine(color, p(20f, 5f), p(20f, 11f), 1.8f * unit)
                drawLine(color, p(20f, 11f), p(14f, 11f), 1.8f * unit)
                drawLine(color, p(4f, 19f), p(4f, 13f), 1.8f * unit)
                drawLine(color, p(4f, 13f), p(10f, 13f), 1.8f * unit)
            }
        }
    }
}

private fun formatTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

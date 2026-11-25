@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kaoyan.planner

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.kaoyan.planner.data.ExamPhaseEntity
import com.kaoyan.planner.data.MilestoneEntity
import com.kaoyan.planner.data.PlanTaskEntity
import com.kaoyan.planner.data.PlanRevisionEntity
import com.kaoyan.planner.data.SubjectEntity
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val WarmBackground = Color(0xFFF7F7F3)
private val Ink = Color(0xFF252A26)
private val MutedInk = Color(0xFF777E77)
private val SoftGreen = Color(0xFFE7EEE8)
private val Accent = Color(0xFF486C57)

class MainActivity : ComponentActivity() {
    private val plannerViewModel: PlannerViewModel by viewModels()
    private var networkAvailable by mutableStateOf(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { updateNetworkStatus() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            runOnUiThread { updateNetworkStatus() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { updateNetworkStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(networkCallback)
        updateNetworkStatus()
        if (Build.VERSION.SDK_INT >= 33) {
            val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Accent,
                onPrimary = Color.White,
                secondary = Color(0xFF738C78),
                background = WarmBackground,
                surface = Color.White,
                onSurface = Ink,
                onBackground = Ink,
            )) {
                PlannerApp(plannerViewModel, networkAvailable)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        networkAvailable = connected
        if (connected) plannerViewModel.synchronize()
    }

    override fun onDestroy() {
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }
}

private enum class PlannerPage { Month, Roadmap, Settings }

@Composable
private fun OnlineRequiredScreen(
    networkAvailable: Boolean,
    cloudConfigured: Boolean,
    signedIn: Boolean,
    syncing: Boolean,
    syncStatus: String,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = WarmBackground) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("联网后继续", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "研途智排需要连接云端并完成同步后才能查看和操作计划。",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(56.dp))
            when {
                !networkAvailable -> Text("当前没有可用的互联网连接，请检查网络后重试。", color = MutedInk, textAlign = TextAlign.Center)
                !cloudConfigured -> Text(
                    "请先在 apps/android/local.properties 中配置 Supabase URL 和 anon key。",
                    color = MutedInk,
                    textAlign = TextAlign.Center,
                )
                signedIn -> {
                    Text(if (syncing) "正在连接并同步云端数据…" else syncStatus, color = MutedInk, textAlign = TextAlign.Center)
                    if (syncing) {
                        Spacer(Modifier.height(18.dp))
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onRetry, enabled = !syncing && networkAvailable) {
                        Text(if (syncing) "同步中" else "重新连接并同步")
                    }
                    TextButton(onClick = onSignOut, enabled = !syncing) { Text("退出登录并更换账号") }
                }
                else -> {
                    Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MutedInk, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSignIn(email.trim(), password) },
                            enabled = !syncing && email.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(if (syncing) "连接中" else "登录并同步") }
                        OutlinedButton(
                            onClick = { onSignUp(email.trim(), password) },
                            enabled = !syncing && email.isNotBlank() && password.length >= 8,
                            modifier = Modifier.weight(1f),
                        ) { Text("注册") }
                    }
                    if (syncing) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannerApp(viewModel: PlannerViewModel, networkAvailable: Boolean) {
    val signedIn by viewModel.signedIn.collectAsState()
    val onlineReady by viewModel.onlineReady.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncing by viewModel.syncing.collectAsState()

    if (!networkAvailable || !onlineReady) {
        OnlineRequiredScreen(
            networkAvailable = networkAvailable,
            cloudConfigured = viewModel.isCloudConfigured(),
            signedIn = signedIn,
            syncing = syncing,
            syncStatus = syncStatus,
            onSignIn = viewModel::signIn,
            onSignUp = viewModel::signUp,
            onRetry = viewModel::synchronize,
            onSignOut = viewModel::signOut,
        )
        return
    }

    var page by remember { mutableStateOf(PlannerPage.Month) }
    val timer by viewModel.timer.collectAsState()
    var showFreeTimer by remember { mutableStateOf(false) }
    var showAddTask by remember { mutableStateOf(false) }
    val subjects by viewModel.subjects.collectAsState(initial = emptyList())
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var calendarExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(timer?.sessionId) {
        while (timer != null) {
            delay(1000)
            viewModel.refreshTimer()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(WarmBackground)) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().background(WarmBackground),
                    containerColor = WarmBackground,
                ) {
                    Spacer(Modifier.height(24.dp))
                    PageRailItem("本月", page == PlannerPage.Month, Icons.Default.Home) { page = PlannerPage.Month }
                    PageRailItem("全程", page == PlannerPage.Roadmap, Icons.Default.Timeline) { page = PlannerPage.Roadmap }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { page = PlannerPage.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    Spacer(Modifier.height(16.dp))
                }
                when (page) {
                    PlannerPage.Month -> MonthPage(viewModel, subjects, selectedDate, { selectedDate = it }, calendarExpanded, { calendarExpanded = !calendarExpanded }, wide, { showFreeTimer = true }, { showAddTask = true }, { page = PlannerPage.Roadmap }, { page = PlannerPage.Settings })
                    PlannerPage.Roadmap -> RoadmapPage(viewModel, wide)
                    PlannerPage.Settings -> SettingsPage(viewModel)
                }
            }
        } else {
            Scaffold(
                containerColor = WarmBackground,
                bottomBar = {
                    NavigationBar(containerColor = WarmBackground) {
                        NavigationBarItem(selected = page == PlannerPage.Month, onClick = { page = PlannerPage.Month }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("本月") })
                        NavigationBarItem(selected = page == PlannerPage.Roadmap, onClick = { page = PlannerPage.Roadmap }, icon = { Icon(Icons.Default.Timeline, null) }, label = { Text("全程") })
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (page) {
                        PlannerPage.Month -> MonthPage(viewModel, subjects, selectedDate, { selectedDate = it }, calendarExpanded, { calendarExpanded = !calendarExpanded }, false, { showFreeTimer = true }, { showAddTask = true }, { page = PlannerPage.Roadmap }, { page = PlannerPage.Settings })
                        PlannerPage.Roadmap -> RoadmapPage(viewModel, false)
                        PlannerPage.Settings -> SettingsPage(viewModel)
                    }
                }
            }
        }
    }

    if (showFreeTimer) {
        FreeTimerDialog(subjects = subjects, onDismiss = { showFreeTimer = false }, onStart = { subject, activity, minutes ->
            viewModel.startFreeTimer(subject, activity, minutes)
            showFreeTimer = false
        }, onManualLog = { subject, activity, minutes, note -> viewModel.addManualSession(subject, activity, minutes, note); showFreeTimer = false })
    }
    if (showAddTask) {
        AddTaskDialog(subjects, selectedDate, onDismiss = { showAddTask = false }, onSave = { subject, title, activity, minutes, time ->
                viewModel.addTask(
                    PlanTaskEntity(planDate = selectedDate.toString(), subjectId = subject.id, title = title, activityType = activity, plannedMinutes = minutes, scheduledStartTime = time.ifBlank { null }),
                )
            showAddTask = false
        })
    }
    if (timer != null) {
        TimerDialog(timer!!, viewModel, onDismiss = { viewModel.finishTimer() })
    }
}

@Composable
private fun PageRailItem(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationRailItem(selected = selected, onClick = onClick, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
}

@Composable
private fun MonthPage(
    viewModel: PlannerViewModel,
    subjects: List<SubjectEntity>,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    calendarExpanded: Boolean,
    onToggleCalendar: () -> Unit,
    wide: Boolean,
    onFreeTimer: () -> Unit,
    onAddTask: () -> Unit,
    onRoadmap: () -> Unit,
    onSettings: () -> Unit,
) {
    val tasks by remember(selectedDate) { viewModel.tasksFor(selectedDate.toString()) }.collectAsState(initial = emptyList())
    val examDate = remember(viewModel.examDate()) { runCatching { LocalDate.parse(viewModel.examDate()) }.getOrNull() }
    val daysLeft = examDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).coerceAtLeast(0) }
    val dateFormat = DateTimeFormatter.ofPattern("M月d日")

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopAppBar(
            title = { Text("本月", fontWeight = FontWeight.SemiBold) },
            actions = {
                TextButton(onClick = onFreeTimer) { Text("计时 / 补记") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmBackground),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(daysLeft?.let { "距初试 $it 天" } ?: "请设置初试日期", color = MutedInk, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text("·", color = MutedInk)
            TextButton(onClick = onRoadmap, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("强化阶段", style = MaterialTheme.typography.bodySmall, color = Accent)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(330.dp)) {
                    DateSelector(selectedDate, onDateSelected, calendarExpanded, onToggleCalendar, dateFormat)
                }
                Column(Modifier.weight(1f)) {
                    TaskList(tasks, subjects, viewModel, selectedDate, onAddTask, onFreeTimer)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                DateSelector(selectedDate, onDateSelected, calendarExpanded, onToggleCalendar, dateFormat)
                TaskList(tasks, subjects, viewModel, selectedDate, onAddTask, onFreeTimer)
            }
        }
    }
}

@Composable
private fun DateSelector(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    calendarExpanded: Boolean,
    onToggleCalendar: () -> Unit,
    dateFormat: DateTimeFormatter,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(selectedDate.format(dateFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleCalendar) {
                Icon(Icons.Default.DateRange, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (calendarExpanded) "收起" else "月历")
            }
        }
        if (calendarExpanded) {
            val dates = remember { (0 until 30).map { LocalDate.now().plusDays(it.toLong()) } }
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth().height(if (selectedDate == LocalDate.now()) 220.dp else 220.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gridItems(dates) { date ->
                    DateCell(date, date == selectedDate, onClick = { onDateSelected(date) })
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (0..6).forEach { offset ->
                    val date = LocalDate.now().plusDays(offset.toLong())
                    DateCell(date, date == selectedDate, Modifier.weight(1f)) { onDateSelected(date) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (calendarExpanded) {
            Card(colors = CardDefaults.cardColors(containerColor = SoftGreen), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("本月目标", style = MaterialTheme.typography.labelLarge, color = Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("稳定推进数学与专业课，完成每周真题复盘。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!calendarExpanded && selectedDate == LocalDate.now()) {
            Text("今日安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DateCell(date: LocalDate, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = if (selected) Accent else Color.Transparent
    val foreground = if (selected) Color.White else Ink
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(background).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1], fontSize = 10.sp, color = if (selected) Color.White.copy(alpha = .8f) else MutedInk)
        Spacer(Modifier.height(3.dp))
        Text(date.dayOfMonth.toString(), fontWeight = FontWeight.Medium, color = foreground, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (selected) Color.White else if (date.dayOfMonth % 3 == 0) Accent.copy(alpha = .55f) else Color.Transparent))
    }
}

@Composable
private fun ColumnScope.TaskList(
    tasks: List<PlanTaskEntity>,
    subjects: List<SubjectEntity>,
    viewModel: PlannerViewModel,
    selectedDate: LocalDate,
    onAddTask: () -> Unit,
    onFreeTimer: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selectedDate == LocalDate.now()) "今日任务" else "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("${tasks.count { it.status == "done" }} / ${tasks.size} 完成", style = MaterialTheme.typography.labelMedium, color = MutedInk)
    }
    if (tasks.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这一天还没有安排", color = MutedInk)
                TextButton(onClick = onAddTask) { Text("添加一项任务") }
            }
        }
    } else {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            lazyColumnItems(tasks, key = { it.id }) { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                TaskCard(task, subject, onStart = { if (subject != null) viewModel.startTask(task, subject) }, onToggleDone = { viewModel.toggleTask(task) })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onFreeTimer, modifier = Modifier.weight(1f)) { Text("自由计时 / 补记") }
        Button(onClick = onAddTask, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("添加任务") }
    }
}

@Composable
private fun TaskCard(task: PlanTaskEntity, subject: SubjectEntity?, onStart: () -> Unit, onToggleDone: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).clip(CircleShape).background(Color(subject?.color ?: 0xFF839386)))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subject?.name ?: "科目", style = MaterialTheme.typography.labelMedium, color = Color(subject?.color ?: 0xFF839386), fontWeight = FontWeight.SemiBold)
                    task.scheduledStartTime?.let { Text("  ·  $it", style = MaterialTheme.typography.labelSmall, color = MutedInk) }
                }
                Spacer(Modifier.height(3.dp))
                Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (task.status == "done") MutedInk else Ink)
                Spacer(Modifier.height(3.dp))
                Text("${task.activityType}  ·  ${task.plannedMinutes} 分钟", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            if (task.status == "done") {
                FilledIconButton(onClick = onToggleDone, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Check, "标记未完成") }
            } else {
                FilledIconButton(onClick = onStart, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PlayArrow, "开始计时") }
            }
        }
    }
}

@Composable
private fun RoadmapPage(viewModel: PlannerViewModel, wide: Boolean) {
    val phases by viewModel.phases.collectAsState(initial = emptyList())
    val milestones by viewModel.milestones.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 20.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
        TopAppBar(title = { Text("全程", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmBackground))
        Text("备考阶段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            lazyColumnItems(phases, key = { it.id }) { phase ->
                PhaseCard(phase, milestones.filter { it.phaseId == phase.id }, viewModel)
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun PhaseCard(phase: ExamPhaseEntity, milestones: List<MilestoneEntity>, viewModel: PlannerViewModel) {
    val status = when (phase.status) { "active" -> "进行中"; "completed" -> "已完成"; else -> "未开始" }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(phase.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Surface(color = if (phase.status == "active") SoftGreen else WarmBackground, shape = CircleShape) {
                    Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (phase.status == "active") Accent else MutedInk)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("${shortDate(phase.startDate)} – ${shortDate(phase.endDate)}   ·   目标 ${phase.targetHours} 小时", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            if (phase.status == "active") {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(SoftGreen))
                Spacer(Modifier.height(12.dp))
            } else Spacer(Modifier.height(9.dp))
            Text(phase.coreGoal, style = MaterialTheme.typography.bodyMedium, color = Ink)
            if (milestones.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                milestones.forEach { milestone ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = milestone.done, onCheckedChange = { viewModel.toggleMilestone(milestone.id, milestone.done) })
                        Column {
                            Text(milestone.subjectName, style = MaterialTheme.typography.labelSmall, color = MutedInk)
                            Text(milestone.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(viewModel: PlannerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionRefresh = remember { mutableIntStateOf(0) }
    val exactAlarmsAllowed = remember(permissionRefresh.intValue) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }
    val notificationsAllowed = remember(permissionRefresh.intValue) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val batteryOptimizationIgnored = remember(permissionRefresh.intValue) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    val openSettings = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRefresh.intValue += 1 }
    var examDate by remember { mutableStateOf(viewModel.examDate()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val signedIn by viewModel.signedIn.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val revisions by viewModel.revisions.collectAsState(initial = emptyList())
    val createdApiKey by viewModel.createdApiKey.collectAsState()
    var revisionToRevert by remember { mutableStateOf<PlanRevisionEntity?>(null) }
    var revokeApiKeyConfirmation by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
        TopAppBar(title = { Text("设置", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmBackground))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("考研档案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = examDate, onValueChange = { examDate = it }, label = { Text("初试日期（YYYY-MM-DD）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { runCatching { LocalDate.parse(examDate) }.onSuccess { viewModel.saveExamDate(examDate) } }, modifier = Modifier.align(Alignment.End)) { Text("保存") }
                Divider()
                Text("提醒", fontWeight = FontWeight.Medium)
                Text("通知：${if (notificationsAllowed) "已允许" else "未允许"} · 精准闹钟：${if (exactAlarmsAllowed) "已允许" else "不可用，将降级"} · 后台限制：${if (batteryOptimizationIgnored) "已忽略电池优化" else "系统可能限制后台提醒"}", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            openSettings.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开通知设置") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        OutlinedButton(
                            onClick = {
                                openSettings.launch(Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开精准闹钟设置") }
                    }
                    if (!batteryOptimizationIgnored) {
                        OutlinedButton(
                            onClick = { openSettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开电池优化设置") }
                    }
                }
                Divider()
                Text("云端同步", fontWeight = FontWeight.Medium)
                Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MutedInk)
                if (!viewModel.isCloudConfigured()) {
                    Text("请在 apps/android/local.properties 配置 Supabase URL 和 anon key。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                } else if (signedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::synchronize, enabled = !syncing) { Text(if (syncing) "同步中" else "立即同步") }
                        OutlinedButton(onClick = viewModel::signOut) { Text("退出登录") }
                    }
                    Divider()
                    Text("电脑端 AI 连接", fontWeight = FontWeight.Medium)
                    Text(
                        "API Key 只在生成时展示一次。远程 MCP 网关部署后才可使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::createApiKey, enabled = !syncing) {
                            Text(if (syncing) "处理中" else if (viewModel.hasApiKeyId) "重新生成 API Key" else "生成 API Key")
                        }
                        if (viewModel.hasApiKeyId) {
                            OutlinedButton(onClick = { revokeApiKeyConfirmation = true }, enabled = !syncing) {
                                Text("吊销")
                            }
                        }
                    }
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.signIn(email.trim(), password) }, enabled = !syncing && email.isNotBlank() && password.isNotBlank()) { Text(if (syncing) "处理中" else "登录并同步") }
                        OutlinedButton(onClick = { viewModel.signUp(email.trim(), password) }, enabled = !syncing && email.isNotBlank() && password.length >= 8) { Text("注册") }
                    }
                }
                Text("登录会从 Supabase 拉取科目、阶段、月计划和任务。任务完成、手动补记及计时记录会在同步时幂等上传。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                Divider()
                Text("最近 AI 调整", fontWeight = FontWeight.Medium)
                if (revisions.isEmpty()) {
                    Text("暂无计划变更", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                } else {
                    revisions.take(5).forEachIndexed { index, revision ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(revision.changeSummary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text("${revision.scope.toRevisionLabel()} · ${revision.createdAt.take(16).replace('T', ' ')}", style = MaterialTheme.typography.labelSmall, color = MutedInk)
                            }
                            if (index == 0 && !revision.isReverted) {
                                TextButton(onClick = { revisionToRevert = revision }, enabled = !syncing) { Text("撤回") }
                            }
                        }
                    }
                }
            }
        }
    }
    createdApiKey?.let { rawKey ->
        AlertDialog(
            onDismissRequest = viewModel::clearCreatedApiKey,
            title = { Text("API Key 已生成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请立即复制并保存。关闭后无法再次查看。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                    Text(rawKey, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(viewModel.mcpClientConfiguration(rawKey)))
                        viewModel.clearCreatedApiKey()
                    }) { Text("复制 MCP 客户端配置并清除") }
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(rawKey))
                        viewModel.clearCreatedApiKey()
                    }) { Text("仅复制 Key 并清除") }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::clearCreatedApiKey) { Text("关闭并清除") }
            },
        )
    }
    if (revokeApiKeyConfirmation) {
        AlertDialog(
            onDismissRequest = { revokeApiKeyConfirmation = false },
            title = { Text("吊销远程 MCP API Key？") },
            text = { Text("吊销后，使用此 Key 的 MCP 客户端将无法继续访问。") },
            confirmButton = {
                TextButton(onClick = {
                    revokeApiKeyConfirmation = false
                    viewModel.revokeApiKey()
                }) { Text("确认吊销") }
            },
            dismissButton = { TextButton(onClick = { revokeApiKeyConfirmation = false }) { Text("取消") } },
        )
    }
    revisionToRevert?.let { revision ->
        AlertDialog(
            onDismissRequest = { revisionToRevert = null },
            title = { Text("撤回最近一次调整？") },
            text = { Text("${revision.changeSummary}\n${revision.reason}\n\n撤回会恢复该次 AI 调整前的计划数据。") },
            confirmButton = {
                TextButton(onClick = { viewModel.revertRevision(revision); revisionToRevert = null }) { Text("确认撤回") }
            },
            dismissButton = { TextButton(onClick = { revisionToRevert = null }) { Text("取消") } },
        )
    }
}

private fun String.toRevisionLabel(): String = when (this) {
    "daily_plans" -> "日计划"
    "exam_phases" -> "全程阶段"
    "monthly_plans" -> "月计划"
    else -> this
}

@Composable
private fun AddTaskDialog(
    subjects: List<SubjectEntity>,
    date: LocalDate,
    onDismiss: () -> Unit,
    onSave: (SubjectEntity, String, String, Int, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    var time by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("刷题") }
    var selectedSubject by remember(subjects) { mutableStateOf(subjects.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("${date.monthValue}月${date.dayOfMonth}日", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                ChoiceMenu(selectedSubject?.name ?: "选择科目", subjects.map { it.name }, onSelect = { name -> selectedSubject = subjects.firstOrNull { it.name == name } })
                OutlinedTextField(title, { title = it }, label = { Text("任务内容") }, singleLine = true)
                ChoiceMenu(activity, listOf("听课", "刷题", "复盘"), onSelect = { activity = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(time, { time = it }, label = { Text("时段 08:30") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { val subject = selectedSubject; val duration = minutes.toIntOrNull() ?: 60; if (subject != null && title.isNotBlank() && duration in 5..480) onSave(subject, title.trim(), activity, duration, time) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FreeTimerDialog(
    subjects: List<SubjectEntity>,
    onDismiss: () -> Unit,
    onStart: (SubjectEntity, String, Int) -> Unit,
    onManualLog: (SubjectEntity, String, Int, String?) -> Unit,
) {
    var manual by remember { mutableStateOf(false) }
    var selected by remember(subjects) { mutableStateOf(subjects.firstOrNull()) }
    var activity by remember { mutableStateOf("刷题") }
    var minutes by remember { mutableIntStateOf(50) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (manual) "手动补记" else "自由计时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { manual = false }) { Text("计时") }
                    TextButton(onClick = { manual = true }) { Text("补记") }
                }
                ChoiceMenu(selected?.name ?: "选择科目", subjects.map { it.name }, onSelect = { name -> selected = subjects.firstOrNull { it.name == name } })
                ChoiceMenu(activity, listOf("听课", "刷题", "复盘"), onSelect = { activity = it })
                if (!manual) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(listOf(25, 50), listOf(90, 180)).forEach { rowPresets ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowPresets.forEach { preset ->
                                    OutlinedButton(onClick = { minutes = preset }, modifier = Modifier.weight(1f)) {
                                        Text(if (preset == 180) "180 分全真模考" else "${preset} 分钟")
                                    }
                                }
                            }
                        }
                    }
                    Text("结束后自动记录有效学习时长。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(minutes.toString(), { minutes = it.toIntOrNull()?.coerceIn(5, 480) ?: 5 }, label = { Text("分钟（5–480）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("简注（可选）") }, singleLine = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let { if (manual) onManualLog(it, activity, minutes, note) else onStart(it, activity, minutes) } }) { Text(if (manual) "记下" else "开始") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChoiceMenu(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
private fun TimerDialog(timer: TimerSnapshot, viewModel: PlannerViewModel, onDismiss: () -> Unit) {
    var now by remember { mutableIntStateOf(0) }
    LaunchedEffect(timer.sessionId) {
        while (true) { delay(1000); now++ }
    }
    val elapsed = remember(timer.sessionId, now) { viewModel.elapsedSeconds(timer) }
    val remaining = (timer.targetDurationSec - elapsed).coerceAtLeast(0)
    Dialog(onDismissRequest = { }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(color = WarmBackground, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("专注计时", color = MutedInk, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(38.dp))
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(timer.subjectColor)))
                Spacer(Modifier.height(16.dp))
                Text(timer.subjectName, style = MaterialTheme.typography.titleLarge, color = Color(timer.subjectColor), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(7.dp))
                Text(timer.taskTitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(42.dp))
                Text(formatClock(if (timer.lastPausedAtEpochMs != null) elapsed else elapsed), fontSize = 62.sp, fontWeight = FontWeight.Light, color = Ink)
                Text("${timer.activityType} · 目标 ${timer.targetDurationSec / 60} 分钟", color = MutedInk)
                if (remaining == 0L) {
                    Spacer(Modifier.height(16.dp))
                    Text("本次计划时间已完成", color = Accent, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(46.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { if (timer.lastPausedAtEpochMs == null) viewModel.pauseTimer() else viewModel.resumeTimer() }) {
                        Text(if (timer.lastPausedAtEpochMs == null) "暂停" else "继续")
                    }
                    Button(onClick = onDismiss) { Text("结束并保存") }
                }
            }
        }
    }
}

private fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun shortDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M月d日"))
}.getOrDefault(value)

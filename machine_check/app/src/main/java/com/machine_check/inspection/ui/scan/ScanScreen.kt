package com.machine_check.inspection.ui.scan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.machine_check.inspection.ui.components.QrCodeScanner

/**
 * 扫码页面
 * 步骤1: 输入/扫描工号 → 步骤2: 输入/扫描设备型号 → 步骤3: 选择频率 → 进入点检
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onNavigateToInspection: (deviceModel: String, employeeId: String, frequency: String, periodKey: String) -> Unit,
    viewModel: ScanViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 导航触发
    LaunchedEffect(uiState.navigateToInspection) {
        uiState.navigateToInspection?.let { navData ->
            val parts = navData.split("/", limit = 3)
            val deviceModel = parts[0]
            val frequency = parts.getOrElse(1) { "日" }
            val periodKey = parts.getOrElse(2) { "" }
            onNavigateToInspection(deviceModel, uiState.employeeId, frequency, periodKey)
            viewModel.onNavigationComplete()
        }
    }

    // 从点检页返回时重新检查频率可用性
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshFrequencies()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备点检") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (uiState.isScanning) {
            // ========== 扫码全屏模式 ==========
            Box(modifier = Modifier.fillMaxSize()) {
                QrCodeScanner(
                    onBarcodeScanned = { barcode -> viewModel.onBarcodeScanned(barcode) },
                    isActive = uiState.isScanning,
                    modifier = Modifier.fillMaxSize()
                )

                // 扫描目标提示
                Text(
                    text = if (uiState.currentScanTarget == ScanTarget.EMPLOYEE_ID)
                        "请扫描工号二维码" else "请扫描设备型号二维码",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 取消按钮
                Button(
                    onClick = { viewModel.stopScanning() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                ) {
                    Text("取消扫码")
                }
            }
        } else {
            // ========== 输入模式 ==========
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ---- 工号区域 ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.employeeValidated)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "步骤 1: 员工工号",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = uiState.employeeId,
                            onValueChange = { viewModel.onEmployeeIdChange(it) },
                            label = { Text("工号") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = uiState.validationError != null,
                            supportingText = if (uiState.validationError != null) {
                                { Text(uiState.validationError!!, color = MaterialTheme.colorScheme.error) }
                            } else if (uiState.employeeValidated) {
                                { Text("✓ 工号验证通过", color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            trailingIcon = {
                                Row {
                                    // 手动验证按钮
                                    if (!uiState.employeeValidated && uiState.employeeId.isNotBlank() && !uiState.isValidatingEmployee) {
                                        TextButton(onClick = {
                                            viewModel.validateEmployeeId(uiState.employeeId)
                                        }) {
                                            Text("验证")
                                        }
                                    }
                                    IconButton(onClick = {
                                        viewModel.startScanning(ScanTarget.EMPLOYEE_ID)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.QrCodeScanner,
                                            contentDescription = "扫描工号二维码"
                                        )
                                    }
                                }
                            }
                        )

                        // 验证中进度条
                        if (uiState.isValidatingEmployee) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                // ---- 设备型号区域 ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "步骤 2: 设备型号",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = uiState.deviceModel,
                            onValueChange = { viewModel.onDeviceModelChange(it) },
                            label = { Text("设备型号") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = uiState.employeeValidated,
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.startScanning(ScanTarget.DEVICE_MODEL)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = "扫描设备型号二维码"
                                    )
                                }
                            }
                        )
                    }
                }

                // ---- 扫码结果提示 ----
                if (uiState.scanResult != null && !uiState.isScanning) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = "扫描结果: ${uiState.scanResult}",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // ---- 步骤 3: 点检频率 ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "步骤 3: 点检频率",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val freqInfo = uiState.frequenciesAvailable

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 日检
                            val dailyAvailable = freqInfo?.daily?.available != false
                            FilterChip(
                                selected = uiState.selectedFrequency == "日",
                                onClick = { viewModel.onFrequencySelected("日") },
                                label = { Text(if (dailyAvailable) "日检" else "日检 ✓") },
                                enabled = dailyAvailable,
                                modifier = Modifier.weight(1f)
                            )

                            // 周检
                            val weeklyAvailable = freqInfo?.weekly?.available != false
                            FilterChip(
                                selected = uiState.selectedFrequency == "周",
                                onClick = { viewModel.onFrequencySelected("周") },
                                label = { Text(if (weeklyAvailable) "周检" else "周检 ✓") },
                                enabled = weeklyAvailable,
                                modifier = Modifier.weight(1f)
                            )

                            // 月检
                            val monthlyAvailable = freqInfo?.monthly?.available != false
                            FilterChip(
                                selected = uiState.selectedFrequency == "月",
                                onClick = { viewModel.onFrequencySelected("月") },
                                label = { Text(if (monthlyAvailable) "月检" else "月检 ✓") },
                                enabled = monthlyAvailable,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 检查中进度条
                if (uiState.isCheckingFrequencies) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---- 进入点检按钮 ----
                val freqInfo = uiState.frequenciesAvailable
                val selectedAvailable = when (uiState.selectedFrequency) {
                    "日" -> freqInfo?.daily?.available != false
                    "周" -> freqInfo?.weekly?.available != false
                    "月" -> freqInfo?.monthly?.available != false
                    else -> false
                }
                Button(
                    onClick = { viewModel.navigateToInspection() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = uiState.employeeValidated
                        && uiState.deviceModel.isNotBlank()
                        && !uiState.isCheckingFrequencies
                        && selectedAvailable
                ) {
                    Text(
                        text = "进入点检",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

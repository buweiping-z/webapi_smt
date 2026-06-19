package com.machine_check.inspection.ui.inspection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.machine_check.inspection.data.models.InspectionTemplate
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionScreen(
    deviceModel: String, employeeId: String,
    frequency: String = "日", periodKey: String = "",
    onBack: () -> Unit,
    viewModel: InspectionViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(deviceModel, frequency) {
        viewModel.loadTemplates(deviceModel, employeeId, frequency, periodKey)
    }

    LaunchedEffect((uiState as? InspectionUiState.Form)?.errorMessage) {
        val msg = (uiState as? InspectionUiState.Form)?.errorMessage
        if (msg != null) { snackbarHostState.showSnackbar(msg); viewModel.clearError() }
    }

    LaunchedEffect((uiState as? InspectionUiState.Form)?.submitSuccess) {
        if ((uiState as? InspectionUiState.Form)?.submitSuccess == true) {
            snackbarHostState.showSnackbar("提交成功！"); onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("点检 - $deviceModel [${frequency}检]") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val state = uiState) {
            is InspectionUiState.Loading -> LoadingView(Modifier.fillMaxSize().padding(padding))
            is InspectionUiState.Error -> ErrorView(state.message, Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                viewModel.retry(deviceModel, employeeId, frequency, periodKey)
            }
            is InspectionUiState.Empty -> EmptyView(Modifier.fillMaxSize().padding(padding))
            is InspectionUiState.Form -> InspectionForm(
                state = state, viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingView(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(); Spacer(modifier = Modifier.height(16.dp))
            Text("正在加载点检模板...")
        }
    }
}

@Composable
private fun ErrorView(message: String, modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyView(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("该设备型号暂无点检模板")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectionForm(
    state: InspectionUiState.Form,
    viewModel: InspectionViewModel,
    modifier: Modifier
) {
    val context = LocalContext.current
    // 相机状态：每个 item 独立管理 URI
    var pendingPhotoItemName by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                val idx = state.items.indexOfFirst { it.template.itemName == pendingPhotoItemName }
                if (idx >= 0) viewModel.onPhotoTaken(idx, uri.path ?: "")
            }
        }
        pendingPhotoItemName = null
    }

    /** 为指定检查项拍照 */
    fun takePhotoForItem(itemName: String) {
        val photoDir = File(context.cacheDir, "inspection_photos")
        photoDir.mkdirs()
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        cameraUri = uri
        pendingPhotoItemName = itemName
        cameraLauncher.launch(uri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 阶段 2: Pending Photo 提示 =====
        if (state.phase2Pending) {
            Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFFF3E0), shadowElevation = 2.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("请逐项拍照上传（${state.uploadedCount}/${state.totalPhotoCount}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE65100),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.skipPhotoUpload() }) {
                            Text("跳过", color = Color(0xFFE65100))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 逐项显示拍照按钮
                    state.pendingPhotoItems.forEach { ppi ->
                        ppi.missingItems.forEach { itemName ->
                            val itemIdx = state.items.indexOfFirst { it.template.itemName == itemName }
                            val itemState = if (itemIdx >= 0) state.items[itemIdx] else null
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (itemState?.photoUploaded == true) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("• $itemName ✓ 已上传", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                                } else if (itemState?.isPhotoUploading == true) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("• $itemName 上传中...", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Icon(Icons.Filled.CameraAlt, null, tint = Color(0xFFBF360C), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("• $itemName", style = MaterialTheme.typography.labelSmall, color = Color(0xFFBF360C))
                                    Spacer(modifier = Modifier.weight(1f))
                                    FilledTonalButton(
                                        onClick = { takePhotoForItem(itemName) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) { Text("拍照", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 表单列表
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items = state.items, key = { _, item -> item.template.id }) { index, itemState ->
                InspectionItemCard(
                    itemState = itemState, index = index,
                    onNormalAbnormalChanged = { viewModel.onNormalAbnormalChanged(index, it) },
                    onNumericValueChanged = { viewModel.onNumericValueChanged(index, it) },
                    onRemarkChanged = { viewModel.onRemarkChanged(index, it) },
                    onTakePhoto = { takePhotoForItem(itemState.template.itemName) },
                    isPhase2 = state.phase2Pending
                )
            }
        }

        // 底部提交按钮
        Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            if (state.phase2Pending) {
                // 阶段 2：显示完成按钮
                Button(
                    onClick = { viewModel.skipPhotoUpload() },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("完成提交（跳过剩余拍照）", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = { viewModel.submitInspection() },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    enabled = !state.isSubmitting
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp)); Text("提交中...")
                    } else {
                        Text("提交点检", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionItemCard(
    itemState: InspectionItemState, index: Int,
    onNormalAbnormalChanged: (Boolean) -> Unit,
    onNumericValueChanged: (String) -> Unit,
    onRemarkChanged: (String) -> Unit,
    onTakePhoto: () -> Unit,
    isPhase2: Boolean
) {
    val template = itemState.template; val isValid = itemState.isValid

    Card(
        modifier = Modifier.fillMaxWidth().border(
            width = if (!isValid) 2.dp else 0.dp,
            color = if (!isValid) MaterialTheme.colorScheme.error else Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 标题行
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${template.itemName}", style = MaterialTheme.typography.titleSmall)
                    if (template.requirePhoto) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE3F2FD)) {
                            Text("📷 需拍照", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF1565C0))
                        }
                    }
                }
                if (!isValid) Text("必填", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            when (template.itemType) {
                "normal_abnormal" -> NormalAbnormalInput(itemState.selectedNormal, onNormalAbnormalChanged)
                "numeric" -> NumericInput(itemState.numericValue, onNumericValueChanged, template.unit, template.normalMin, template.normalMax, itemState.isOutOfRange)
            }

            OutlinedTextField(value = itemState.remark, onValueChange = onRemarkChanged,
                label = { if (itemState.remarkRequired) Text("备注（必填）", color = MaterialTheme.colorScheme.error) else Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = itemState.remarkRequired && itemState.remark.isBlank()
            )

            // ===== 照片区域 =====
            if (template.requirePhoto) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 照片预览
                if (itemState.photoLocalPath != null) {
                    AsyncImage(
                        model = File(itemState.photoLocalPath), contentDescription = "照片预览",
                        modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit
                    )
                }

                // 拍照按钮（阶段 2 pending 时不重复显示——已在顶部 banner 中）
                if (!isPhase2) {
                    OutlinedButton(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (itemState.photoLocalPath != null) "重新拍照" else "拍照")
                    }
                }
            }
        }
    }
}

@Composable
private fun NormalAbnormalInput(selectedNormal: Boolean?, onSelectionChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onSelectionChanged(true) }, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedNormal == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Text("✓ 正常", color = if (selectedNormal == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Button(onClick = { onSelectionChanged(false) }, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedNormal == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Text("✗ 异常", color = if (selectedNormal == false) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun NumericInput(value: String, onValueChanged: (String) -> Unit, unit: String?, normalMin: Double?, normalMax: Double?, isOutOfRange: Boolean = false) {
    val numericValue = value.toDoubleOrNull()
    val isInRange = when {
        value.isBlank() -> null; numericValue == null -> false; isOutOfRange -> false
        normalMin != null && normalMax != null -> numericValue >= normalMin && numericValue <= normalMax
        else -> true
    }
    var flashCount by remember { mutableStateOf(0) }; var isFlashing by remember { mutableStateOf(false) }
    LaunchedEffect(isOutOfRange) {
        if (isOutOfRange) { flashCount = 0; isFlashing = true; repeat(6) { flashCount++; delay(150) }; isFlashing = false }
    }
    val flashBorderColor = if (isFlashing && flashCount % 2 == 0) Color.Transparent
    else if (isOutOfRange) Color(0xFFF44336)
    else when (isInRange) { null -> MaterialTheme.colorScheme.outline; true -> Color(0xFF4CAF50); false -> Color(0xFFF44336) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChanged,
            label = { Text(buildString { if (normalMin != null && normalMax != null) append("范围: $normalMin ~ $normalMax"); if (unit != null) { if (isNotEmpty()) append(" "); append(unit) } }.ifEmpty { "请输入数值" }) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = isOutOfRange,
            supportingText = if (isOutOfRange) {{ Text("数值超出正常范围，将作为异常记录", color = Color(0xFFF44336)) }}
                else if (isInRange == false) {{ Text("数值超出正常范围", color = Color(0xFFF44336)) }} else null,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = flashBorderColor, unfocusedBorderColor = flashBorderColor))
    }
}

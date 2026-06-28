package com.machine_check.inspection.ui.inspection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.machine_check.inspection.data.models.InspectionTemplate
import com.machine_check.inspection.data.models.MAX_PHOTOS_PER_ITEM
import com.machine_check.inspection.data.network.RetrofitClient
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
    var pendingPhotoItemName by remember { mutableStateOf<String?>(null) }
    var pendingPhotoFilePath by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showPhotoReminder by remember { mutableStateOf(false) }
    var missingPhotoItemNames by remember { mutableStateOf<List<String>>(emptyList()) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val filePath = pendingPhotoFilePath
            val itemName = pendingPhotoItemName
            if (filePath != null && itemName != null) {
                val idx = state.items.indexOfFirst { it.template.itemName == itemName }
                if (idx >= 0) viewModel.onPhotoTaken(idx, filePath)
            }
        }
        pendingPhotoItemName = null
        pendingPhotoFilePath = null
    }

    /** 为指定检查项拍照 */
    fun takePhotoForItem(itemName: String) {
        val photoDir = File(context.cacheDir, "inspection_photos")
        photoDir.mkdirs()
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhotoFilePath = photoFile.absolutePath  // 保存真实路径
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
                        Text("异常项必须拍照上传（${state.uploadedCount}/${state.totalPhotoCount}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE65100),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 逐项显示拍照按钮
                    state.pendingPhotoItems.forEach { ppi ->
                        ppi.missingItems.forEach { missingItem ->
                            val itemName = missingItem.itemName
                            val itemIdx = state.items.indexOfFirst { it.template.itemName == itemName }
                            val itemState = if (itemIdx >= 0) state.items[itemIdx] else null
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (itemState?.isPhotoUploading == true) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("• $itemName 上传中...", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    val count = itemState?.photoUploadedCount ?: 0
                                    val canTakeMore = count < MAX_PHOTOS_PER_ITEM
                                    Icon(
                                        if (count > 0) Icons.Filled.CheckCircle else Icons.Filled.CameraAlt,
                                        null,
                                        tint = if (count > 0) Color(0xFF4CAF50) else Color(0xFFBF360C),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (count > 0) "• $itemName 已上传 $count 张" else "• $itemName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (count > 0) Color(0xFF4CAF50) else Color(0xFFBF360C)
                                    )
                                    if (canTakeMore) {
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
                    onRemovePhoto = { photoIndex -> viewModel.removeLocalPhoto(index, photoIndex) },
                    isPhase2 = state.phase2Pending
                )
            }
        }

        // 底部提交按钮
        Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            if (state.phase2Pending) {
                // 阶段 2：所有异常项至少 1 张照片后可手动完成
                val allCovered = state.pendingPhotoItems.all { ppi ->
                    ppi.missingItems.all { mi ->
                        val idx = state.items.indexOfFirst { it.template.itemName == mi.itemName }
                        idx >= 0 && state.items[idx].photoUploadedCount > 0
                    }
                }
                Button(
                    onClick = { viewModel.finishPhase2() },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    enabled = allCovered,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allCovered) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                ) {
                    Text(
                        if (allCovered) "完成提交" else "请先完成拍照上传（${state.uploadedCount}/${state.totalPhotoCount}）",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            } else {
                Button(
                    onClick = {
                        // 提交前检查：异常 + requirePhoto 但没有本地照片的项
                        val missing = state.items.filter { item ->
                            item.template.requirePhoto &&
                                item.selectedNormal == false &&
                                item.photoLocalPaths.isEmpty()
                        }.map { it.template.itemName }
                        if (missing.isNotEmpty()) {
                            missingPhotoItemNames = missing
                            showPhotoReminder = true
                        } else {
                            viewModel.submitInspection()
                        }
                    },
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

        // ===== 提交前拍照提醒弹窗 =====
        if (showPhotoReminder) {
            AlertDialog(
                onDismissRequest = { showPhotoReminder = false },
                title = { Text("📷 以下异常项需要拍照") },
                text = {
                    Text(missingPhotoItemNames.joinToString("、"))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPhotoReminder = false
                        viewModel.submitInspection()
                    }) { Text("仍要提交") }
                },
                dismissButton = {
                    TextButton(onClick = { showPhotoReminder = false }) {
                        Text("去拍照")
                    }
                }
            )
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
    onRemovePhoto: (Int) -> Unit,
    isPhase2: Boolean
) {
    val template = itemState.template; val isValid = itemState.isValid

    val photoBaseUrl = RetrofitClient.baseUrl.trimEnd('/')
    var showFullScreenDialog by remember { mutableStateOf(false) }
    var fullScreenPhotoUrl by remember { mutableStateOf("") }

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

            // ===== 定位照片区域 =====
            val positionPhotos = template.positionPhotos
                .filter { !it.thumbnailPath.isNullOrBlank() }
                .sortedBy { it.photoOrder }
            if (positionPhotos.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("📍 定位指示", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    positionPhotos.forEach { photo ->
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    // 全屏查看原图
                                    showFullScreenDialog = true
                                    fullScreenPhotoUrl = "${photoBaseUrl}/${photo.photoPath.trimStart('/')}"
                                }
                        ) {
                            AsyncImage(
                                model = "${photoBaseUrl}/${photo.thumbnailPath!!.trimStart('/')}",
                                contentDescription = "定位照片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // ===== 照片区域 =====
            if (template.requirePhoto) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 缩略图网格 + 拍照按钮
                val paths = itemState.photoLocalPaths
                if (paths.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 缩略图网格（每行 3 张）
                        val rows = paths.chunked(3)
                        for (row in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (path in row) {
                                    val globalIdx = paths.indexOf(path)
                                    Box(modifier = Modifier.weight(1f).height(80.dp)) {
                                        AsyncImage(
                                            model = File(path),
                                            contentDescription = "照片 ${globalIdx + 1}",
                                            modifier = Modifier.fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentScale = ContentScale.Crop
                                        )
                                        // 删除按钮（右上角）
                                        IconButton(
                                            onClick = { onRemovePhoto(globalIdx) },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .padding(2.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color.Black.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "删除",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                // 填充空位保持对齐
                                repeat(3 - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // 拍照按钮（Phase 2 pending 时不重复显示 — 已在顶部 banner 中）
                if (!isPhase2) {
                    val count = itemState.photoLocalPaths.size
                    OutlinedButton(
                        onClick = onTakePhoto,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = count < MAX_PHOTOS_PER_ITEM
                    ) {
                        Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                count >= MAX_PHOTOS_PER_ITEM -> "已拍满 $MAX_PHOTOS_PER_ITEM 张"
                                count > 0 -> "📷 拍照（${count}/$MAX_PHOTOS_PER_ITEM）"
                                else -> "拍照"
                            }
                        )
                    }
                }
            }
        }
    }

    // 全屏查看定位照片
    if (showFullScreenDialog) {
        Dialog(
            onDismissRequest = { showFullScreenDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullScreenDialog = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = fullScreenPhotoUrl,
                    contentDescription = "定位照片全屏",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
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

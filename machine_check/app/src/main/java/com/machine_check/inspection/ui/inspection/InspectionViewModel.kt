package com.machine_check.inspection.ui.inspection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.machine_check.inspection.data.models.InspectionTemplate
import com.machine_check.inspection.data.models.PendingPhotoItem
import com.machine_check.inspection.data.models.SaveDailyRecordRequest
import com.machine_check.inspection.data.models.SaveRecordItem
import com.machine_check.inspection.data.repository.InspectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 单个点检项的表单状态
 */
data class InspectionItemState(
    val template: InspectionTemplate,
    val selectedNormal: Boolean? = null,
    val numericValue: String = "",
    val remark: String = "",
    val isValid: Boolean = true,
    val remarkRequired: Boolean = false,
    val isOutOfRange: Boolean = false,
    // 照片相关
    val photoLocalPath: String? = null,
    val isPhotoUploading: Boolean = false,
    val photoUploaded: Boolean = false      // 上传成功标记
)

/**
 * 点检页面 UI 状态
 */
sealed interface InspectionUiState {
    data object Loading : InspectionUiState
    data class Form(
        val deviceModel: String,
        val employeeId: String,
        val frequency: String = "日",
        val periodKey: String = "",
        val items: List<InspectionItemState>,
        val isSubmitting: Boolean = false,
        val submitSuccess: Boolean = false,
        val errorMessage: String? = null,
        // 两阶段提交 — 第二阶段
        val phase2Pending: Boolean = false,         // 需要拍照
        val pendingPhotoItems: List<PendingPhotoItem> = emptyList(),
        val uploadedCount: Int = 0,
        val totalPhotoCount: Int = 0
    ) : InspectionUiState
    data class Error(val message: String) : InspectionUiState
    data class Empty(val deviceModel: String) : InspectionUiState
}

/**
 * 点检页面 ViewModel
 */
class InspectionViewModel : ViewModel() {

    private val repository = InspectionRepository()

    private val _uiState = MutableStateFlow<InspectionUiState>(InspectionUiState.Loading)
    val uiState: StateFlow<InspectionUiState> = _uiState.asStateFlow()

    fun loadTemplates(deviceModel: String, employeeId: String, frequency: String = "日", periodKey: String = "") {
        _uiState.value = InspectionUiState.Loading
        viewModelScope.launch {
            repository.getTemplates(deviceModel, frequency).fold(
                onSuccess = { templates ->
                    if (templates.isEmpty()) {
                        _uiState.value = InspectionUiState.Empty(deviceModel)
                    } else {
                        val sortedTemplates = templates.sortedBy { it.sortOrder }
                        val items = sortedTemplates.map { InspectionItemState(template = it) }
                        _uiState.value = InspectionUiState.Form(
                            deviceModel = deviceModel, employeeId = employeeId,
                            frequency = frequency, periodKey = periodKey, items = items
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.value = InspectionUiState.Error(error.message ?: "未知错误")
                }
            )
        }
    }

    fun onNormalAbnormalChanged(itemIndex: Int, isNormal: Boolean) {
        val state = _uiState.value as? InspectionUiState.Form ?: return
        val updatedItems = state.items.toMutableList()
        updatedItems[itemIndex] = updatedItems[itemIndex].copy(
            selectedNormal = isNormal, isValid = true, remarkRequired = !isNormal
        )
        _uiState.update { (it as InspectionUiState.Form).copy(items = updatedItems) }
    }

    fun onNumericValueChanged(itemIndex: Int, value: String) {
        val state = _uiState.value as? InspectionUiState.Form ?: return
        val numValue = value.toDoubleOrNull()
        val template = state.items[itemIndex].template
        val outOfRange = numValue != null &&
            ((template.normalMin != null && numValue < template.normalMin) ||
             (template.normalMax != null && numValue > template.normalMax))
        val updatedItems = state.items.toMutableList()
        updatedItems[itemIndex] = updatedItems[itemIndex].copy(
            numericValue = value, isValid = true, isOutOfRange = outOfRange
        )
        _uiState.update { (it as InspectionUiState.Form).copy(items = updatedItems) }
    }

    fun onRemarkChanged(itemIndex: Int, remark: String) {
        val state = _uiState.value as? InspectionUiState.Form ?: return
        val updatedItems = state.items.toMutableList()
        updatedItems[itemIndex] = updatedItems[itemIndex].copy(remark = remark)
        _uiState.update { (it as InspectionUiState.Form).copy(items = updatedItems) }
    }

    /** 拍照回调 — 两阶段提交第二阶段：拍照后即时上传 */
    fun onPhotoTaken(itemIndex: Int, filePath: String) {
        val state = _uiState.value as? InspectionUiState.Form ?: return

        val updatedItems = state.items.toMutableList()
        updatedItems[itemIndex] = updatedItems[itemIndex].copy(
            photoLocalPath = filePath, isPhotoUploading = true
        )
        _uiState.update { (it as InspectionUiState.Form).copy(items = updatedItems) }

        // 即时压缩 + 上传
        viewModelScope.launch {
            val item = updatedItems[itemIndex]
            val itemName = item.template.itemName

            // 从 pendingPhotoItems 找到对应的 recordId
            val recordId = state.pendingPhotoItems
                .firstOrNull { ppi -> ppi.missingItems.contains(itemName) }
                ?.recordId ?: 0
            if (recordId == 0) {
                updatedItems[itemIndex] = updatedItems[itemIndex].copy(isPhotoUploading = false)
                _uiState.update { (it as InspectionUiState.Form).copy(items = updatedItems) }
                return@launch
            }

            // 压缩
            val compressedPath = compressImage(filePath)
            val uploadPath = compressedPath ?: filePath

            val result = repository.uploadPhoto(
                filePath = uploadPath, recordId = recordId,
                itemName = itemName, uploadedBy = state.employeeId
            )

            // 清理压缩临时文件
            if (compressedPath != null && compressedPath != filePath) {
                File(compressedPath).delete()
            }

            result.fold(
                onSuccess = {
                    val items2 = (_uiState.value as? InspectionUiState.Form)?.items?.toMutableList() ?: return@launch
                    items2[itemIndex] = items2[itemIndex].copy(
                        isPhotoUploading = false, photoUploaded = true
                    )
                    val newUploaded = ( _uiState.value as? InspectionUiState.Form)?.uploadedCount?.plus(1) ?: 1
                    val total = ( _uiState.value as? InspectionUiState.Form)?.totalPhotoCount ?: 0
                    _uiState.update {
                        (it as InspectionUiState.Form).copy(
                            items = items2, uploadedCount = newUploaded
                        )
                    }
                    // 全部上传完成 → 自动成功
                    if (newUploaded >= total) {
                        _uiState.update {
                            (it as InspectionUiState.Form).copy(phase2Pending = false, submitSuccess = true)
                        }
                    }
                },
                onFailure = {
                    val items2 = (_uiState.value as? InspectionUiState.Form)?.items?.toMutableList() ?: return@launch
                    items2[itemIndex] = items2[itemIndex].copy(isPhotoUploading = false)
                    _uiState.update { (it as InspectionUiState.Form).copy(items = items2) }
                }
            )
        }
    }

    fun clearError() {
        val state = _uiState.value as? InspectionUiState.Form ?: return
        _uiState.update { state.copy(errorMessage = null) }
    }

    // ========== 两阶段提交 ==========

    /**
     * 阶段 1: 保存文字结果到 records/save（不强制拍照）
     * 阶段 2: 如有 pendingPhotoItems → 引导用户逐项拍照上传
     */
    fun submitInspection() {
        val state = _uiState.value as? InspectionUiState.Form ?: return

        // ===== 验证（仅文字校验，不强制拍照——两阶段提交） =====
        val validatedItems = state.items.mapIndexed { _, item ->
            val template = item.template
            val baseValid = when (template.itemType) {
                "normal_abnormal" -> {
                    val selected = item.selectedNormal != null
                    if (selected && item.selectedNormal == false && item.remark.isBlank()) false
                    else selected
                }
                "numeric" -> item.numericValue.toDoubleOrNull() != null
                else -> true
            }
            item.copy(isValid = baseValid, remarkRequired = item.selectedNormal == false)
        }

        val hasErrors = validatedItems.any { !it.isValid }
        if (hasErrors) {
            _uiState.update {
                (it as InspectionUiState.Form).copy(
                    items = validatedItems,
                    errorMessage = "异常项必须填写备注，或数值格式不正确"
                )
            }
            return
        }

        // ===== 构建 records/save 请求 =====
        val today = java.time.LocalDate.now().dayOfMonth
        val results = validatedItems.map { item ->
            val template = item.template
            val (resultValue, isNormal) = when (template.itemType) {
                "normal_abnormal" -> {
                    val normal = item.selectedNormal ?: true
                    (if (normal) "正常" else "异常") to normal
                }
                "numeric" -> {
                    val numOk = item.numericValue.toDoubleOrNull() != null
                    if (numOk) {
                        val numVal = item.numericValue.toDouble()
                        val inRange = when {
                            template.normalMin != null && template.normalMax != null ->
                                numVal >= template.normalMin && numVal <= template.normalMax
                            else -> true
                        }
                        item.numericValue to inRange
                    } else item.numericValue to true
                }
                else -> "" to true
            }
            SaveRecordItem(day = today, itemName = template.itemName,
                resultValue = resultValue, remark = item.remark)
        }

        val request = SaveDailyRecordRequest(
            employeeId = state.employeeId, deviceModel = state.deviceModel,
            inspectionMonth = java.time.LocalDate.now().toString(), results = results
        )

        _uiState.update { (it as InspectionUiState.Form).copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            repository.saveDailyRecord(request).fold(
                onSuccess = { response ->
                    if (response.pendingPhotoItems.isEmpty()) {
                        // 无缺照片项 → 直接完成
                        _uiState.update {
                            (it as InspectionUiState.Form).copy(isSubmitting = false, submitSuccess = true)
                        }
                    } else {
                        // 进入阶段 2：提示用户逐项拍照
                        val totalPhotos = response.pendingPhotoItems.sumOf { it.missingItems.size }
                        _uiState.update {
                            (it as InspectionUiState.Form).copy(
                                isSubmitting = false,
                                phase2Pending = true,
                                pendingPhotoItems = response.pendingPhotoItems,
                                totalPhotoCount = totalPhotos,
                                uploadedCount = 0
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        (it as InspectionUiState.Form).copy(
                            isSubmitting = false,
                            errorMessage = error.message ?: "提交失败，请重试"
                        )
                    }
                }
            )
        }
    }

    /** 跳过拍照，直接完成 */
    fun skipPhotoUpload() {
        _uiState.update {
            val s = it as? InspectionUiState.Form ?: return
            s.copy(phase2Pending = false, submitSuccess = true)
        }
    }

    // ========== 图片处理 ==========

    private fun compressImage(filePath: String): String? {
        return try {
            val original = BitmapFactory.decodeFile(filePath) ?: return null
            if (original.width <= 1920) return null // 不需要压缩
            val ratio = 1920f / original.width.toFloat()
            val width = 1920
            val height = (original.height * ratio).toInt()
            val resized = Bitmap.createScaledBitmap(original, width, height, true)
            if (resized != original) original.recycle()

            val compressedFile = File(filePath + "_compressed.jpg")
            FileOutputStream(compressedFile).use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            resized.recycle()
            if (compressedFile.length() > 0) compressedFile.absolutePath else null
        } catch (e: Exception) { null }
    }

    fun retry(deviceModel: String, employeeId: String, frequency: String = "日", periodKey: String = "") {
        loadTemplates(deviceModel, employeeId, frequency, periodKey)
    }
}

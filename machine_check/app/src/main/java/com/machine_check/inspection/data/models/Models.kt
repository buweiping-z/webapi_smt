package com.machine_check.inspection.data.models

/**
 * 点检模板（从服务端获取的单条点检项）
 */
data class InspectionTemplate(
    val id: Int,
    val deviceModel: String,
    val itemName: String,
    val itemType: String,          // "normal_abnormal" 或 "numeric"
    val unit: String?,
    val normalMin: Double?,
    val normalMax: Double?,
    val sortOrder: Int,
    val frequency: String = "日",   // 点检频率: 日/周/月
    val requirePhoto: Boolean = false  // 该检查项是否需要拍照
)

/**
 * 提交点检的完整请求体
 */
data class FullInspectionRequest(
    val employeeId: String,
    val deviceModel: String,
    val results: List<InspectionResultItem>,
    val frequency: String = "日",   // 点检频率
    val periodKey: String = ""      // 周期键，由后端生成
)

/**
 * 单条点检结果
 */
data class InspectionResultItem(
    val itemName: String,
    val resultValue: String,
    val isNormal: Boolean,
    val remark: String
)

/**
 * 工号验证响应
 */
data class ValidateResponse(
    val valid: Boolean,
    val employeeId: String = "",
    val lastName: String = ""
)

/**
 * 服务端提交成功响应
 */
data class SubmitResponse(
    val message: String,
    val success: Boolean
)

/**
 * 单个频率的可用状态
 */
data class FrequencyStatus(
    val available: Boolean,
    val periodKey: String = ""
)

/**
 * 设备各频率可用状态响应
 */
data class FrequenciesAvailableResponse(
    val daily: FrequencyStatus,
    val weekly: FrequencyStatus,
    val monthly: FrequencyStatus
)

/**
 * 单条未点检设备信息（频率 + location）
 */
data class UninspectedDeviceItem(
    val frequency: String,   // "日检" / "周检" / "月检"
    val location: String     // device_location
)

/**
 * 未点检必须设备列表响应
 */
data class UninspectedMandatoryResponse(
    val uninspectedList: List<UninspectedDeviceItem>,
    val abnormalList: List<UninspectedDeviceItem>
)

/**
 * 当月完全未点检设备信息（跨所有频率，零记录）
 */
data class UninspectedMonthlyDevice(
    val deviceModel: String,
    val deviceName: String,
    val deviceLocation: String
)

/**
 * 当月未点检设备列表响应
 */
data class UninspectedMonthlyResponse(
    val year: Int,
    val month: Int,
    val totalDevices: Int,
    val uninspectedCount: Int,
    val uninspectedDevices: List<UninspectedMonthlyDevice>
)

// ==================== 照片功能相关模型 ====================

/**
 * records/save 请求体中的单条记录
 */
data class SaveRecordItem(
    val day: Int,
    val itemName: String,
    val resultValue: String,
    val isNormal: Boolean,
    val remark: String
)

/**
 * records/save 请求体
 */
data class SaveDailyRecordRequest(
    val employeeId: String,
    val deviceModel: String,
    val inspectionMonth: String,    // "2026-06-19"
    val frequency: String = "日",   // 日/周/月
    val results: List<SaveRecordItem>
)

/**
 * records/save 端点响应（含照片状态）
 */
data class SaveRecordResponse(
    val success: Boolean,
    val message: String,
    val recordIds: List<Int> = emptyList(),
    val pendingPhotoItems: List<PendingPhotoItem> = emptyList()
)

/**
 * 缺照片的检查项
 */
data class PendingPhotoItem(
    val recordId: Int,
    val periodKey: String = "",
    val missingItems: List<String>
)

/**
 * 照片上传响应
 */
data class PhotoUploadResponse(
    val success: Boolean,
    val photoId: Int = 0,
    val photoPath: String = "",
    val thumbnailPath: String = ""
)

/**
 * 月度照片列表中的单条照片
 */
data class MonthlyPhoto(
    val id: Int,
    val recordId: Int,
    val itemName: String,
    val photoPath: String,
    val thumbnailPath: String,
    val photoOrder: Int,
    val uploadedBy: String,
    val createdAt: String
)

/**
 * 单个点检项的照片状态（UI 层使用）
 */
data class PhotoItemState(
    val localFilePath: String? = null,      // 本地拍摄的照片路径（待上传）
    val uploadedPhotoIds: List<Int> = emptyList(),  // 已上传的照片 ID
    val isUploading: Boolean = false,       // 是否正在上传
    val uploadError: String? = null         // 上传失败错误信息
)


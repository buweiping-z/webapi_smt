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
    val frequency: String = "日"    // 点检频率: 日/周/月
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


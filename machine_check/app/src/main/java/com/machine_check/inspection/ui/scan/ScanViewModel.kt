package com.machine_check.inspection.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.machine_check.inspection.data.repository.InspectionRepository
import com.machine_check.inspection.utils.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 扫码页面 UI 状态
 */
data class ScanUiState(
    val employeeId: String = "",
    val deviceModel: String = "",
    val selectedFrequency: String = "日",
    val frequenciesAvailable: com.machine_check.inspection.data.models.FrequenciesAvailableResponse? = null,
    val isCheckingFrequencies: Boolean = false,
    val currentScanTarget: ScanTarget = ScanTarget.EMPLOYEE_ID,
    val isScanning: Boolean = false,
    val scanResult: String? = null,
    val navigateToInspection: String? = null,  // "deviceModel/frequency/periodKey"
    // 工号验证状态
    val isValidatingEmployee: Boolean = false,
    val employeeValidated: Boolean = false,
    val validationError: String? = null
)

/**
 * 扫码目标（工号 or 设备型号）
 */
enum class ScanTarget {
    EMPLOYEE_ID,
    DEVICE_MODEL
}

/**
 * 扫码页面 ViewModel
 */
class ScanViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager = PreferencesManager(application),
    private val repository: InspectionRepository = InspectionRepository()
) : AndroidViewModel(application) {
    private var freqCheckJob: Job? = null

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    init {
        // 启动时加载已保存的工号
        viewModelScope.launch {
            val savedEmployeeId = preferencesManager.employeeId.first()
            _uiState.update { it.copy(employeeId = savedEmployeeId) }
        }
    }

    /** 更新工号输入 */
    fun onEmployeeIdChange(employeeId: String) {
        _uiState.update {
            it.copy(
                employeeId = employeeId,
                employeeValidated = false,
                validationError = null
            )
        }
    }

    /** 更新设备型号输入，自动检查频率可用性 */
    fun onDeviceModelChange(deviceModel: String) {
        _uiState.update { it.copy(deviceModel = deviceModel) }
        checkFrequenciesAvailable(deviceModel)
    }

    /** 打开扫码器 — 设置扫码目标（扫设备前需验证工号） */
    fun startScanning(target: ScanTarget) {
        if (target == ScanTarget.DEVICE_MODEL && !_uiState.value.employeeValidated) {
            // 工号未验证，先尝试验证当前工号
            val currentId = _uiState.value.employeeId.trim()
            if (currentId.isEmpty()) {
                _uiState.update { it.copy(validationError = "请先输入并验证工号") }
                return
            }
            _uiState.update { it.copy(validationError = "工号未验证，请先扫码验证工号") }
            return
        }
        _uiState.update {
            it.copy(
                currentScanTarget = target,
                isScanning = true,
                scanResult = null
            )
        }
    }

    /** 关闭扫码器 */
    fun stopScanning() {
        _uiState.update {
            it.copy(isScanning = false, scanResult = null)
        }
    }

    /** 扫码结果回调 */
    fun onBarcodeScanned(barcode: String) {
        when (_uiState.value.currentScanTarget) {
            ScanTarget.EMPLOYEE_ID -> {
                _uiState.update {
                    it.copy(
                        employeeId = barcode,
                        scanResult = barcode,
                        isScanning = false,
                        employeeValidated = false,
                        validationError = null
                    )
                }
                // 保存工号到 DataStore，并立即验证
                viewModelScope.launch {
                    preferencesManager.saveEmployeeId(barcode)
                    validateEmployeeId(barcode)
                }
            }
            ScanTarget.DEVICE_MODEL -> {
                val model = barcode
                _uiState.update {
                    it.copy(
                        deviceModel = model,
                        scanResult = model,
                        isScanning = false
                    )
                }
                // 扫码到设备型号后立即检查频率可用性
                checkFrequenciesAvailable(model)
            }
        }
    }

    /** 选择点检频率（不可用时忽略） */
    fun onFrequencySelected(frequency: String) {
        val status = _uiState.value.frequenciesAvailable ?: return
        val available = when (frequency) {
            "日" -> status.daily.available
            "周" -> status.weekly.available
            "月" -> status.monthly.available
            else -> false
        }
        if (available) {
            _uiState.update { it.copy(selectedFrequency = frequency) }
        }
    }

    /** 导航到点检页面（携带 periodKey） */
    fun navigateToInspection() {
        val state = _uiState.value
        val deviceModel = state.deviceModel.trim()
        val frequency = state.selectedFrequency
        if (deviceModel.isEmpty()) return

        val freqInfo = state.frequenciesAvailable
        val periodKey = when (frequency) {
            "日" -> freqInfo?.daily?.periodKey ?: ""
            "周" -> freqInfo?.weekly?.periodKey ?: ""
            "月" -> freqInfo?.monthly?.periodKey ?: ""
            else -> ""
        }
        _uiState.update { it.copy(navigateToInspection = "$deviceModel/$frequency/$periodKey") }
    }

    /** 导航完成回调（重置导航状态） */
    fun onNavigationComplete() {
        _uiState.update { it.copy(navigateToInspection = null) }
    }

    /** 从点检页返回时刷新频率可用性 */
    fun refreshFrequencies() {
        val model = _uiState.value.deviceModel
        if (model.isNotBlank()) {
            checkFrequenciesAvailable(model)
        }
    }

    // ===== 私有方法 =====

    /** 验证工号是否为点检资格人员 */
    fun validateEmployeeId(employeeId: String) {
        if (employeeId.isBlank()) return
        _uiState.update { it.copy(isValidatingEmployee = true, validationError = null) }
        viewModelScope.launch {
            repository.validateEmployee(employeeId).fold(
                onSuccess = { valid ->
                    _uiState.update {
                        it.copy(
                            isValidatingEmployee = false,
                            employeeValidated = valid,
                            validationError = if (valid) null else "该工号无点检资格"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isValidatingEmployee = false,
                            employeeValidated = false,
                            validationError = e.message ?: "验证失败"
                        )
                    }
                }
            )
        }
    }

    /** 查询设备各频率的可用状态（300ms 防抖 + 自动取消前一个请求） */
    private fun checkFrequenciesAvailable(deviceModel: String) {
        if (deviceModel.isBlank()) return

        // 取消前一个未完成的请求
        freqCheckJob?.cancel()

        freqCheckJob = viewModelScope.launch {
            // 300ms 防抖：等待用户停止输入
            delay(300)

            _uiState.update { it.copy(isCheckingFrequencies = true) }
            repository.getFrequenciesAvailable(deviceModel).fold(
                onSuccess = { result ->
                    _uiState.update {
                        var state = it.copy(
                            isCheckingFrequencies = false,
                            frequenciesAvailable = result
                        )
                        // 如果当前选中的频率已不可用，自动切到第一个可用的
                        val sel = it.selectedFrequency
                        if (sel == "日" && !result.daily.available) {
                            val fallback = when {
                                result.weekly.available -> "周"
                                result.monthly.available -> "月"
                                else -> "日"
                            }
                            state = state.copy(selectedFrequency = fallback)
                        }
                        if (sel == "周" && !result.weekly.available) {
                            state = state.copy(selectedFrequency = "日")
                        }
                        if (sel == "月" && !result.monthly.available) {
                            state = state.copy(selectedFrequency = "日")
                        }
                        state
                    }
                },
                onFailure = {
                    _uiState.update { s -> s.copy(isCheckingFrequencies = false) }
                }
            )
        }
    }
}

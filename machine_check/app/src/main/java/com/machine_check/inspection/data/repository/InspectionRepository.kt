package com.machine_check.inspection.data.repository

import com.machine_check.inspection.data.models.FrequenciesAvailableResponse
import com.machine_check.inspection.data.models.FullInspectionRequest
import com.machine_check.inspection.data.models.InspectionTemplate
import com.machine_check.inspection.data.models.SubmitResponse
import com.machine_check.inspection.data.network.ApiService
import com.machine_check.inspection.data.network.RetrofitClient

/**
 * 点检数据仓库
 * 封装网络请求，统一处理成功/失败结果
 *
 * @param apiService 可通过构造函数注入以支持测试
 */
class InspectionRepository(
    private val api: ApiService = RetrofitClient.apiService
) {
    // 模板缓存：key = "deviceModel:frequency", value = cached result
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<InspectionTemplate>>>()

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long = 5 * 60 * 1000): Boolean =  // 5 分钟过期
            System.currentTimeMillis() - timestamp > ttlMs
    }

    /** 获取指定设备型号的点检模板列表（带内存缓存，5 分钟过期） */
    suspend fun getTemplates(deviceModel: String, frequency: String = "日"): Result<List<InspectionTemplate>> {
        val cacheKey = "$deviceModel:$frequency"

        // 命中缓存且未过期
        val cached = templateCache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            return Result.success(cached.data)
        }

        return try {
            val response = api.getTemplates(deviceModel, frequency)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    templateCache[cacheKey] = CacheEntry(body)
                    Result.success(body)
                } else {
                    Result.failure(
                        Exception("获取模板失败: 服务端返回了空响应体")
                    )
                }
            } else {
                Result.failure(
                    Exception("获取模板失败: ${response.code()} ${response.message()}")
                )
            }
        } catch (e: Exception) {
            // 网络失败时尝试返回过期缓存
            if (cached != null) {
                Result.success(cached.data)
            } else {
                Result.failure(Exception("网络连接失败，请检查网络设置", e))
            }
        }
    }

    /** 验证工号是否为点检资格人员 */
    suspend fun validateEmployee(employeeId: String): Result<Boolean> {
        return try {
            val response = api.validateOperator(employeeId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body.valid)
                } else {
                    Result.failure(Exception("验证失败: 服务端返回了空响应体"))
                }
            } else {
                Result.failure(
                    Exception("验证失败: ${response.code()} ${response.message()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络连接失败，请检查网络设置", e))
        }
    }

    /** 提交完整点检记录 */
    suspend fun submitInspection(request: FullInspectionRequest): Result<SubmitResponse> {
        return try {
            val response = api.submitFullInspection(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(
                        Exception("提交失败: 服务端返回了空响应体")
                    )
                }
            } else {
                // TODO: 考虑使用自定义异常类型替代 Exception
                Result.failure(
                    Exception("提交失败: ${response.code()} ${response.message()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络连接失败，请检查网络设置", e))
        }
    }

    /** 获取设备各频率可用状态 */
    suspend fun getFrequenciesAvailable(deviceModel: String): Result<FrequenciesAvailableResponse> {
        return try {
            val response = api.getFrequenciesAvailable(deviceModel)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("获取频率状态失败: 服务端返回了空响应体"))
                }
            } else {
                Result.failure(Exception("获取频率状态失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络连接失败，请检查网络设置", e))
        }
    }
}

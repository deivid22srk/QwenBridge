package com.deivid22srk.qwenbridge.utils

import com.deivid22srk.qwenbridge.database.LogDao
import com.deivid22srk.qwenbridge.database.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(private val logDao: LogDao) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun info(message: String) = log("INFO", message)
    fun warn(message: String) = log("WARN", message)
    fun error(message: String) = log("ERROR", message)
    fun debug(message: String) = log("DEBUG", message)

    private fun log(level: String, message: String) {
        android.util.Log.println(
            when (level) {
                "ERROR" -> android.util.Log.ERROR
                "WARN" -> android.util.Log.WARN
                "INFO" -> android.util.Log.INFO
                else -> android.util.Log.DEBUG
            },
            "QwenBridge",
            "[$level] $message"
        )
        scope.launch {
            logDao.insertLog(LogEntity(level = level, message = message))
        }
    }
}

package com.coderred.andclaw.proroot

enum class ExecutionRuntime(val storageValue: String) {
    PROROOT("proroot"),
    PROOT("proot");

    companion object {
        fun fromStorageValue(value: String?): ExecutionRuntime = when (value?.trim()?.lowercase()) {
            PROOT.storageValue -> PROOT
            else -> PROROOT
        }
    }
}

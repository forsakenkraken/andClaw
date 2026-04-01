package com.coderred.andclaw.proot.installer

import java.io.File

data class TarInstallSpec(
    val assetName: String,
    val cacheDir: File,
    val destinationDir: File,
    val permissionRootDir: File,
    val stripComponents: Int = 0,
)


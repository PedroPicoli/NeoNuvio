package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import java.io.File
import java.util.zip.ZipFile

internal enum class AppAbiBase {
    ARM64,
    ARMEABI,
    X86_64,
    X86,
    UNIVERSAL,
    UNKNOWN
}

internal object AbiSelector {

    fun detectInstalledBase(context: Context?): AppAbiBase {
        if (context == null) return AppAbiBase.UNKNOWN
        try {
            val sourceDir = context.applicationInfo?.sourceDir
            if (!sourceDir.isNullOrBlank()) {
                val file = File(sourceDir)
                if (file.exists() && file.canRead()) {
                    ZipFile(file).use { zip ->
                        val abisFound = mutableSetOf<String>()
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name
                            if (name.startsWith("lib/")) {
                                val parts = name.split('/')
                                if (parts.size >= 2) {
                                    val abi = parts[1].lowercase()
                                    if (abi in listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")) {
                                        abisFound.add(abi)
                                    }
                                }
                            }
                        }
                        if (abisFound.size > 1) {
                            return AppAbiBase.UNIVERSAL
                        } else if (abisFound.size == 1) {
                            return when (abisFound.first()) {
                                "arm64-v8a" -> AppAbiBase.ARM64
                                "armeabi-v7a", "armeabi" -> AppAbiBase.ARMEABI
                                "x86_64" -> AppAbiBase.X86_64
                                "x86" -> AppAbiBase.X86
                                else -> AppAbiBase.UNKNOWN
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Ignored - fallback to nativeLibraryDir inspection below
        }

        val nativeLib = context.applicationInfo?.nativeLibraryDir.orEmpty().lowercase()
        return when {
            nativeLib.contains("arm64") -> AppAbiBase.ARM64
            nativeLib.contains("arm") -> AppAbiBase.ARMEABI
            nativeLib.contains("x86_64") -> AppAbiBase.X86_64
            nativeLib.contains("x86") -> AppAbiBase.X86
            else -> AppAbiBase.UNKNOWN
        }
    }

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        context: Context? = null
    ): GitHubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val installedBase = detectInstalledBase(context)

        // 1. Tentar selecionar a mesma base instalada atualmente
        val exactMatch = when (installedBase) {
            AppAbiBase.UNIVERSAL -> apkAssets.firstOrNull { isUniversal(it.name) }
            AppAbiBase.ARM64 -> apkAssets.firstOrNull { isArm64(it.name) }
            AppAbiBase.ARMEABI -> apkAssets.firstOrNull { isArmeabi(it.name) }
            AppAbiBase.X86_64 -> apkAssets.firstOrNull { isX86_64(it.name) }
            AppAbiBase.X86 -> apkAssets.firstOrNull { isX86(it.name) }
            AppAbiBase.UNKNOWN -> null
        }
        if (exactMatch != null) return exactMatch

        // 2. Se a release não tiver a base idêntica, tentar a melhor compatível com o dispositivo
        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()
        for (abi in supported) {
            val candidate = apkAssets.firstOrNull { asset ->
                when (abi.lowercase()) {
                    "arm64-v8a" -> isArm64(asset.name)
                    "armeabi-v7a", "armeabi" -> isArmeabi(asset.name)
                    "x86_64" -> isX86_64(asset.name)
                    "x86" -> isX86(asset.name)
                    else -> asset.name.contains(abi, ignoreCase = true)
                }
            }
            if (candidate != null) return candidate
        }

        // 3. Fallback para APK Universal
        val universal = apkAssets.firstOrNull { isUniversal(it.name) }
        if (universal != null) return universal

        // 4. Fallback para APK genérico sem menção a ABI
        val noAbiMention = apkAssets.firstOrNull { asset ->
            !isArm64(asset.name) && !isArmeabi(asset.name) && !isX86_64(asset.name) && !isX86(asset.name)
        }
        return noAbiMention ?: apkAssets.first()
    }

    private fun isUniversal(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("universal") || n.contains("all")
    }

    private fun isArm64(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("arm64") || n.contains("aarch64")
    }

    private fun isArmeabi(name: String): Boolean {
        val n = name.lowercase()
        return (n.contains("armeabi") || n.contains("armv7") || n.contains("arm-v7a")) && !isArm64(name)
    }

    private fun isX86_64(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("x86_64") || n.contains("x64")
    }

    private fun isX86(name: String): Boolean {
        val n = name.lowercase()
        return (n.contains("x86") || n.contains("i386") || n.contains("i686")) && !isX86_64(name)
    }
}

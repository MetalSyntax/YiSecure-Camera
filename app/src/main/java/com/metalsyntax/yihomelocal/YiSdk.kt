package com.metalsyntax.yihomelocal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * El APK de release no trae el SDK P2P de Yi, sus .so ni sus claves (son de Yi/Kami, no se pueden redistribuir):
 * se toman en ejecución de **Kami Home** (o Yi Home) instalada en el mismo teléfono, añadiendo su APK y sus
 * librerías al ClassLoader de la app (patrón de Pulsix con VeryFit). En debug todo va dentro del APK.
 */
object YiSdk {
    /** Kami primero (SDK más nuevo); Yi Home trae el mismo SDK y las mismas claves. */
    val PACKAGES = listOf("com.yitechnology.kamihome", "com.ants360.yicamera.international")

    /** Paquete del que se cargó el SDK (null = viene dentro del APK o no se pudo). */
    var source: String? = null
        private set

    /** true si las clases del SDK se pueden usar (incluidas en el APK o cargadas de Kami/Yi Home). */
    fun load(ctx: Context): Boolean {
        if (hasSdk()) return true
        val ai = PACKAGES.firstNotNullOfOrNull { info(ctx, it) } ?: return false
        return runCatching {
            val loader = ctx.classLoader
            val cls = Class.forName("dalvik.system.BaseDexClassLoader")
            // ponytail: API oculta (lista gris, accesible hasta Android 15, igual que Pulsix); si la cierran, DexClassLoader + reflexión
            cls.getMethod("addDexPath", String::class.java).invoke(loader, ai.sourceDir)
            // addNativePath está bloqueada con targetSdk 34 (hiddenapi "max-target-o"): las .so se copian de Kami
            // a la carpeta privada y se cargan con System.load desde loadLib()
            hasSdk().also { if (it) { source = ai.packageName; kami = ai; appCtx = ctx.applicationContext ?: ctx } } // en attachBaseContext applicationContext aún es null
        }.onFailure { Log.w("YiSdk", "No se pudo cargar el SDK de ${ai.packageName}: $it") }.getOrDefault(false)
    }

    /** Clave HMAC del login de la API de Yi: la de compilación (debug) o la de Kami (ae.d2.b().a). */
    val loginKey: String by lazy {
        BuildConfig.YI_LOGIN_KEY.ifEmpty {
            runCatching {
                val c = Class.forName("ae.d2")
                val inst = c.getMethod("b").invoke(null)
                c.getDeclaredField("a").apply { isAccessible = true }.get(inst) as String
            }.onFailure { Log.w("YiSdk", "Sin clave de login: $it") }.getOrDefault("")
        }
    }

    /** Tabla XOR de la clave Wi-Fi del QR: la de compilación o el campo estático de 70 caracteres de ae.k. */
    val pairXor: String by lazy {
        BuildConfig.YI_PAIR_XOR.ifEmpty {
            runCatching {
                Class.forName("ae.k").declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                    .mapNotNull { f -> f.isAccessible = true; f.get(null) as? String }
                    .single { it.length == 70 && it.all(Char::isLetterOrDigit) }
            }.onFailure { Log.w("YiSdk", "Sin tabla del QR: $it") }.getOrDefault("")
        }
    }

    private var kami: ApplicationInfo? = null
    private var appCtx: Context? = null

    /**
     * Carga una .so del SDK: la del APK (debug) o la copia de la de Kami (release). Las dependencias se resuelven
     * por soname entre las ya cargadas, así que quien llama respeta el orden (c++_shared antes que webrtc_apm).
     */
    @Synchronized
    fun loadLib(name: String) {
        val ai = kami
        if (ai == null) { System.loadLibrary(name); return }
        System.load(extract(ai, name).path)
    }

    /** Copia lib<name>.so del split de Kami (o su carpeta de librerías) a no_backup/yilib/<versión>/, una vez. */
    private fun extract(ai: ApplicationInfo, name: String): java.io.File {
        val ctx = appCtx ?: error("YiSdk sin contexto")
        val ver = runCatching { ctx.packageManager.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val dir = java.io.File(ctx.noBackupFilesDir, "yilib/$ver").apply { mkdirs() }
        val out = java.io.File(dir, "lib$name.so")
        if (out.length() > 0) return out
        // versiones viejas de la copia: fuera
        dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
        java.io.File(ai.nativeLibraryDir ?: "", "lib$name.so").takeIf { it.canRead() }?.let { return it.copyTo(out, true) }
        val abis = android.os.Build.SUPPORTED_ABIS.toList()
        for (apk in listOf(ai.sourceDir) + (ai.splitSourceDirs ?: emptyArray())) {
            java.util.zip.ZipFile(apk).use { z ->
                for (abi in abis) {
                    val e = z.getEntry("lib/$abi/lib$name.so") ?: continue
                    val tmp = java.io.File(dir, "lib$name.so.tmp")
                    z.getInputStream(e).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    tmp.renameTo(out)
                    return out
                }
            }
        }
        throw UnsatisfiedLinkError("lib$name.so no está en ${ai.packageName}")
    }

    private fun info(ctx: Context, pkg: String): ApplicationInfo? =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()

    private fun hasSdk() = runCatching { Class.forName("com.xiaoyi.camera.sdk.AntsCameraManage") }.isSuccess
}

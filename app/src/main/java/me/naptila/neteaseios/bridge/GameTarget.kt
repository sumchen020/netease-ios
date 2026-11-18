package me.naptila.neteaseios.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale

data class InstalledGame(
    val spec: GameSpec,
    val versionName: String = "",
    val icon: ImageBitmap? = null,
    val installed: Boolean = false,
    val isSystemApp: Boolean = false,
    val lastUpdateTime: Long = 0L
)

data class GameTarget(
    val spec: GameSpec,
    val appLabel: String,
    val dataDir: String,
    val versionName: String,
    val gmActivityClass: String,
    val cacheUriPrefix: String
) {
    fun cacheUri(fileName: String): Uri = Uri.parse("$cacheUriPrefix$fileName")

    fun localCacheFilePath(fileName: String): String = "$dataDir/cache/$fileName"

    companion object {
        private val iconCache = LruCache<String, ImageBitmap>(80)

        fun verify(context: Context, spec: GameSpec): GameTarget {
            val pm = context.packageManager
            val info = getPackageInfo(pm, spec.packageName)
            val appInfo = info.applicationInfo
                ?: throw IllegalStateException("game-interface-missing")
            val dataDir = appInfo.dataDir
                ?: throw IllegalStateException("game-interface-missing")
            val label = appInfo.loadLabel(pm).toString().ifBlank { spec.displayName }

            return GameTarget(
                spec = spec.copy(displayName = label),
                appLabel = label,
                dataDir = dataDir,
                versionName = info.versionName ?: "",
                gmActivityClass = spec.gmActivity,
                cacheUriPrefix = spec.cacheProviderUriPrefix
            )
        }

        fun inspect(
            context: Context,
            spec: GameSpec,
            loadIcon: Boolean = true
        ): InstalledGame {
            val pm = context.packageManager
            val info = try {
                getPackageInfo(pm, spec.packageName)
            } catch (_: Exception) {
                return InstalledGame(spec = spec, installed = false)
            }

            val appInfo = info.applicationInfo
            val label = appInfo?.loadLabel(pm)?.toString()?.ifBlank { spec.displayName } ?: spec.displayName
            val icon = if (loadIcon) loadAppIcon(context, spec.packageName) else iconCache.get(spec.packageName)
            val isSystem = appInfo != null &&
                (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

            return InstalledGame(
                spec = spec.copy(displayName = label),
                versionName = info.versionName ?: "",
                icon = icon,
                installed = true,
                isSystemApp = isSystem,
                lastUpdateTime = info.lastUpdateTime
            )
        }

        /**
         * Lists all launcher apps on the device so the user can directly choose any installed game.
         * User-installed apps are listed first, sorted by display name.
         */
        fun listInstalledLauncherApps(context: Context): List<InstalledGame> {
            val pm = context.packageManager
            val selfPkg = context.packageName
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }

            val seen = HashSet<String>()
            val apps = ArrayList<InstalledGame>(resolveList.size)
            for (ri in resolveList) {
                val actInfo = ri.activityInfo ?: continue
                val pkg = actInfo.packageName ?: continue
                if (pkg == selfPkg || !seen.add(pkg)) continue

                val appInfo = actInfo.applicationInfo
                val label = runCatching {
                    appInfo?.loadLabel(pm)?.toString()?.ifBlank { pkg }
                }.getOrNull() ?: pkg
                val isSystem = appInfo != null &&
                    (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val info = runCatching { getPackageInfo(pm, pkg) }.getOrNull()

                apps.add(
                    InstalledGame(
                        spec = GameSpec(packageName = pkg, displayName = label),
                        versionName = info?.versionName.orEmpty(),
                        icon = iconCache.get(pkg),
                        installed = true,
                        isSystemApp = isSystem,
                        lastUpdateTime = info?.lastUpdateTime ?: 0L
                    )
                )
            }

            val collator = Collator.getInstance(Locale.CHINA)
            return apps.sortedWith { a, b ->
                if (a.isSystemApp != b.isSystemApp) {
                    if (a.isSystemApp) 1 else -1
                } else {
                    collator.compare(a.spec.displayName, b.spec.displayName)
                }
            }
        }

        fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
            iconCache.get(packageName)?.let { return it }
            val bmp = runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawableToImageBitmap(drawable)
            }.getOrNull() ?: return null
            iconCache.put(packageName, bmp)
            return bmp
        }

        fun open(context: Context, packageName: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }

        private fun drawableToImageBitmap(drawable: Drawable, sizePx: Int = 96): ImageBitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            return bitmap.asImageBitmap()
        }
    }
}

package me.naptila.neteaseios.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.naptila.neteaseios.BuildConfig

/**
 * Short in-app help. Official game UI and server results remain authoritative.
 * External references moved here from the home header to reduce clutter.
 */
@Composable
internal fun HelpDialog(
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    diagnosticsAvailable: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            shape = RoundedCornerShape(24.dp),
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outlineVariant),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "使用说明",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "快速操作",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "选择游戏 → 打开游戏 → 确认登录 → 检测并准备 iOS 登录 → 返回游戏重新登录并核对角色。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "提示：确认浮窗仅用于继续操作，助手仍通过游戏接口核验账号；SDK 无回调时不能判定 iOS 登录成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )

                    HorizontalDivider()
                    Text(
                        "常见问题",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    HelpFaq(
                        title = "切换账号后，如何恢复 iOS 角色？",
                        answer = "切号后如果变回安卓角色，在游戏「切换账号」列表中找到原账号，再点击登录一次，尝试恢复 iOS 角色。"
                    )
                    HelpFaq(
                        title = "如何切回安卓角色？",
                        answer = "在游戏「切换账号」列表中删除该账号的本地登录记录，再使用原账号重新登录。"
                    )
                    HelpFaq(
                        title = "如何更换授权目录？",
                        answer = "在助手首页点击游戏下方的「目录已授权」，确认「取消授权」后，准备区域会重新显示目录授权入口。点击「授权」，选择需要使用的目录并确认即可。"
                    )
                    HelpFaq(
                        title = "游戏桥接通信超时怎么办？",
                        answer = "请先返回游戏保持登录状态，检查是否开启省电模式。若反复出现通信超时，可在系统「应用信息 → 电池」中检查助手的后台限制，必要时设为「不限制」后重试。不同手机设置名称可能不同。"
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            "切换角色前，请确保能够重新登录原账号。更换目录授权不会删除游戏文件；实际角色以游戏内显示为准。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    HorizontalDivider()
                    Text(
                        "相关链接",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    uriHandler.openUri("https://github.com/Naptila/netease-ios")
                                }
                            }
                        ) { Text("GitHub") }
                        TextButton(
                            onClick = {
                                runCatching {
                                    uriHandler.openUri("https://space.bilibili.com/330170655")
                                }
                            }
                        ) { Text("BiliBili") }
                    }
                    if (diagnosticsAvailable) {
                        TextButton(onClick = onOpenDiagnostics) { Text("遇到问题？查看诊断日志") }
                    }

                    HorizontalDivider()
                    Text(
                        "关于",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "应用版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n" +
                            "构建类型 ${BuildConfig.BUILD_TYPE}\n" +
                            "Actions 构建编号 ${BuildConfig.ACTIONS_RUN_NUMBER.ifEmpty { "未记录" }}\n" +
                            "提交 SHA ${BuildConfig.SOURCE_COMMIT.ifEmpty { "未记录" }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpFaq(title: String, answer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            answer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

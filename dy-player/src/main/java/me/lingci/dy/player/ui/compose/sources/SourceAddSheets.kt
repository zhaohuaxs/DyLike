package me.lingci.dy.player.ui.compose.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.entity.StorageType

/** 新增资源库时支持的来源类型，顺序即展示顺序。 */
private val addableTypes = listOf(StorageType.WEBDAV, StorageType.SMB)

private fun typeIcon(type: StorageType): ImageVector = when (type) {
    StorageType.WEBDAV -> Icons.Default.CloudQueue
    else -> Icons.Default.Storage
}

private fun typeLabelRes(type: StorageType): Int = when (type) {
    StorageType.SMB -> R.string.hint_source_smb_add
    else -> R.string.hint_source_dav_add
}

/**
 * 新增资源库的第一步：先选来源类型。
 *
 * 旧版是 SourceFragment 右下角 FAB → ItemActionDialog 的两项列表，这里改为底部弹窗，
 * 交互一致（先选 WebDAV / SMB，再进表单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceTypeSheet(
    onPick: (StorageType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.hint_source_add),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            addableTypes.forEach { type ->
                SheetActionRow(
                    icon = typeIcon(type),
                    text = stringResource(typeLabelRes(type)),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { onPick(type) }
                )
            }
        }
    }
}

/**
 * 长按资源库条目后的操作列表，对齐旧版 SourceFragment 的 ItemActionDialog
 * （编辑资源库 / 删除资源库）。内置的本地与串流源不开放这两个动作，由调用方过滤。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceActionSheet(
    title: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            SheetActionRow(
                icon = Icons.Default.Edit,
                text = stringResource(R.string.hint_source_action_edit),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onEdit
            )
            SheetActionRow(
                icon = Icons.Default.Delete,
                text = stringResource(R.string.hint_source_action_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    text: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}

/**
 * 新增 WebDAV / SMB 资源库的表单。
 *
 * 所有校验与字段默认值都对齐旧版 [me.lingci.dy.player.ui.source.WebdavManagerDialog] /
 * [me.lingci.dy.player.ui.source.SmbManagerDialog]，避免迁移后行为漂移：
 * 名称留空用默认名、账号密码留空填 guest、WebDAV 只收 http(s) 地址、SMB 只校验非空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFormSheet(
    type: StorageType,
    editing: SourceData? = null,
    onSave: (SourceData) -> Unit,
    onDismiss: () -> Unit
) {
    val isSmb = type == StorageType.SMB
    val isEdit = editing != null && editing.id.isNotBlank()
    val scope = rememberCoroutineScope()

    // 编辑态要把 siteUrl 拆回「服务器 / 共享 / 端口」三个输入框，规则必须与 buildSource 的拼接互逆
    val restoreKey = editing?.id
    val editHost = remember(restoreKey) {
        editing?.siteUrl.orEmpty().removePrefix("smb://")
            .substringBefore('/')
            .substringBefore(':')
    }
    val editShare = remember(restoreKey) {
        editing?.schema.orEmpty().ifBlank {
            editing?.siteUrl.orEmpty().removePrefix("smb://").substringAfter('/', "")
        }.trim('/')
    }
    val editPort = remember(restoreKey) {
        editing?.port.orEmpty().ifBlank {
            editing?.siteUrl.orEmpty().removePrefix("smb://")
                .substringBefore('/')
                .substringAfter(':', "")
        }
    }

    var name by rememberSaveable(type, restoreKey) { mutableStateOf(editing?.title.orEmpty()) }
    var site by rememberSaveable(type, restoreKey) {
        mutableStateOf(if (isSmb) editHost else editing?.siteUrl.orEmpty())
    }
    var share by rememberSaveable(type, restoreKey) { mutableStateOf(editShare) }
    var port by rememberSaveable(type, restoreKey) {
        mutableStateOf(if (isSmb) editPort else "")
    }
    var domain by rememberSaveable(type, restoreKey) { mutableStateOf("") }
    var username by rememberSaveable(type, restoreKey) {
        mutableStateOf(editing?.username.orEmpty())
    }
    var password by rememberSaveable(type, restoreKey) {
        mutableStateOf(editing?.password.orEmpty())
    }

    var error by remember { mutableStateOf<String?>(null) }
    var testState by remember { mutableStateOf(TestState.Idle) }
    var testing by remember { mutableStateOf(false) }

    val siteRequired = stringResource(R.string.hint_source_site_required)
    val siteInvalid = stringResource(R.string.hint_source_site_invalid)
    val defaultName = stringResource(
        if (isSmb) R.string.default_source_smb_name else R.string.default_source_dav_name
    )
    val title = stringResource(
        when {
            isSmb && isEdit -> R.string.hint_source_smb_edit
            isSmb -> R.string.hint_source_smb_add
            isEdit -> R.string.hint_source_dav_edit
            else -> R.string.hint_source_dav_add
        }
    )

    fun buildSource(): SourceData {
        val source = SourceData()
        source.type = type
        editing?.let { source.id = it.id }
        source.title = name.trim().ifBlank { defaultName }
        source.username = username.trim().ifBlank { "guest" }
        source.password = password.trim().ifBlank { "guest" }
        if (isSmb) {
            val host = site.trim()
                .removePrefix("smb://")
                .substringBefore('/')
            val shareName = share.trim().trim('/')
            source.port = port.trim()
            source.schema = shareName
            source.siteUrl = buildString {
                append("smb://")
                append(host)
                if (port.isNotBlank()) append(":").append(port.trim())
                if (shareName.isNotBlank()) append("/").append(shareName)
            }
        } else {
            source.siteUrl = site.trim()
        }
        source.id = LibraryCompat.sourceId(source)
        return source
    }

    fun validate(): Boolean {
        val value = site.trim()
        if (value.isBlank()) {
            error = siteRequired
            return false
        }
        if (!isSmb && !AppUtil.isUrl(value)) {
            error = siteInvalid
            return false
        }
        error = null
        return true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 字段较多，半展开态会把确认按钮推到屏幕外，直接全展开
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hint_dav_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it; error = null },
                    label = {
                        Text(stringResource(if (isSmb) R.string.hint_smb_server else R.string.hint_dav_site))
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (isSmb) R.string.hint_smb_server_desc else R.string.hint_dav_site_desc
                            )
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isSmb) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = share,
                        onValueChange = { share = it },
                        label = { Text(stringResource(R.string.hint_smb_share)) },
                        supportingText = { Text(stringResource(R.string.hint_smb_share_desc)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                            label = { Text(stringResource(R.string.hint_smb_port)) },
                            supportingText = { Text(stringResource(R.string.hint_smb_port_desc)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(140.dp)
                        )
                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it },
                            label = { Text(stringResource(R.string.hint_smb_domain)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.hint_dav_username)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (isSmb) R.string.hint_smb_username_desc else R.string.hint_dav_username_desc
                            )
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.hint_dav_password)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (isSmb) R.string.hint_smb_password_desc else R.string.hint_dav_password_desc
                            )
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (!validate()) return@OutlinedButton
                            testing = true
                            testState = TestState.Idle
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { buildSource().toStorage()?.testConnect() == true }
                                        .getOrDefault(false)
                                }
                                testing = false
                                testState = if (ok) TestState.Success else TestState.Failure
                            }
                        },
                        enabled = !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(14.dp).height(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.hint_dav_test))
                    }
                    Spacer(Modifier.width(12.dp))
                    when (testState) {
                        TestState.Success -> Text(
                            text = stringResource(R.string.hint_source_test_success),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )

                        TestState.Failure -> Text(
                            text = stringResource(R.string.hint_source_test_failure),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )

                        TestState.Idle -> Unit
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (!validate()) return@Button
                        onSave(buildSource())
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.action_confirmed))
                }
            }
        }
    }
}

private enum class TestState { Idle, Success, Failure }

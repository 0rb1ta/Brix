package app.brix.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.brix.core.diagnostics.Diagnostics
import app.brix.streaming.DeviceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Паспорт устройства: что умеет ЭТО железо — и кнопка отдать его нам.
 *
 * **Зачем экран вообще существует.** Всё, что мы знаем о поведении приложения,
 * получено на одном Samsung S21. Два вывода, на которых стоит половина решений
 * в коде — «CBR не держит ни один энкодер» и «капсюли называются bottom/back», —
 * верны для этого телефона и неизвестно, верны ли для остальных. Спросить об
 * этом текстом в issue бесполезно: человек не обязан уметь читать `dumpsys`.
 * Поэтому собираем сами и даём поделиться одним нажатием.
 *
 * **Почему отчёт показывается целиком, а не уходит молча.** Это требование из
 * докстринга самого [DeviceReport], и оно не формальность: мы просим человека
 * прислать данные о его устройстве, и единственный честный способ — показать,
 * что именно уедет, до того как оно уедет. Отправки в обход экрана нет.
 */
@Composable
fun DevicePassportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }

    // Опрос MediaCodecList и всех камер — блокирующий и небыстрый: на S21 это
    // десяток физических сенсоров, у каждого свой список режимов. На UI-потоке
    // это заметный фриз при открытии экрана.
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { DeviceReport.render(context) }.getOrElse { "" }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                crumb(R.string.settings_about, R.string.settings_device_passport),
                onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.device_passport_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            val text = report
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    // JSON с отступами читается только моноширинным, и переносить
                    // его по словам нельзя — строки уезжают горизонтально.
                    text = text ?: stringResource(R.string.device_passport_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                enabled = !text.isNullOrEmpty(),
                onClick = {
                    val body = text ?: return@FilledTonalButton
                    // Запись файла — не на UI-потоке, как и в экспорте диагностики.
                    scope.launch(Dispatchers.IO) {
                        val uri = Diagnostics.exportText(context, "device_passport", body)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.device_passport_share))
            }
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.kormium.sample.sqldemo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.himanshoe.charty.color.ChartyColor
import com.himanshoe.charty.color.ChartyColors
import com.himanshoe.charty.line.LineChart
import com.himanshoe.charty.line.config.LineChartConfig
import com.himanshoe.charty.line.data.LineData
import com.himanshoe.charty.pie.PieChart
import com.himanshoe.charty.pie.config.PieChartConfig
import com.himanshoe.charty.pie.data.PieData
import io.github.kormium.sample.sqldemo.github.GithubRepository
import io.github.kormium.sample.sqldemo.github.LICENSES
import io.github.kormium.sample.sqldemo.github.RepoDashboard
import io.github.kormium.sample.sqldemo.github.RepoRow
import io.github.kormium.sample.sqldemo.github.EcosystemStats
import io.github.kormium.sample.sqldemo.github.RepoSort
import io.github.kormium.sample.sqldemo.github.RepoStatus
import io.github.kormium.sample.sqldemo.github.YEAR_MIN
import io.github.kormium.sample.sqldemo.github.YEAR_MAX
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MONTHS =
    listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val PALETTE = listOf(
    Color(0xFF6366F1), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF06B6D4),
    Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFF84CC16), Color(0xFFF97316),
)

private val SCHEME = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    background = Color(0xFFF1F5F9),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF64748B),
)

private val Muted = Color(0xFF64748B)
private val CardBorder = Color(0xFFE5E9F0)

/** The two datasets the demo can query. GitHub is the headline; synthetic sales is the instant, no-download mode. */
private enum class Dataset(val title: String) { GITHUB("Kotlin ecosystem"), SALES("Synthetic sales") }

/** A dataset-agnostic query-log line, so one QueryLogCard serves both screens. */
data class LogLine(val label: String, val sql: String, val ms: Long)

@Composable
private fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content,
    )

/** Open a URL in a new browser tab (repo links in the table). */
private fun openUrl(url: String) {
    js("window.open(url, '_blank', 'noopener')")
}

private fun Long.grouped(): String = toString().reversed().chunked(3).joinToString(",").reversed()
private fun money(cents: Int): String = "$${cents / 100}.${pad2(cents % 100)}"
private fun dollars(cents: Long): String = "$" + (cents / 100).grouped()

@Composable
fun App() {
    var dataset by remember { mutableStateOf(Dataset.GITHUB) }
    MaterialTheme(colorScheme = SCHEME) {
        Column(Modifier.fillMaxSize().background(SCHEME.background)) {
            when (dataset) {
                Dataset.GITHUB -> GithubScreen(dataset) { dataset = it }
                Dataset.SALES -> SalesScreen(dataset) { dataset = it }
            }
        }
    }
}

@Composable
private fun SalesScreen(dataset: Dataset, onDataset: (Dataset) -> Unit) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<SalesRepository?>(null) }
    var building by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var target by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(0) }
    var genMs by remember { mutableStateOf(0L) }

    var minAmount by remember { mutableStateOf(0) }
    var category by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SortCol.AMOUNT) }
    var asc by remember { mutableStateOf(false) }

    var dashboard by remember { mutableStateOf<Dashboard?>(null) }
    var sample by remember { mutableStateOf<List<OrderRow>>(emptyList()) }
    var queryJob by remember { mutableStateOf<Job?>(null) }
    var queryLog by remember { mutableStateOf<List<QueryLogEntry>>(emptyList()) }
    var buildPhase by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { repo = SalesRepository.open() }

    fun appendLog(entry: QueryLogEntry) {
        queryLog = (listOf(entry) + queryLog).take(30)
    }

    // Two queries per refresh, run concurrently: the rows table (indexed, ~ms) and ONE dashboard
    // cube query that all three charts + KPI roll up from (see SalesRepository.dashboard — one
    // scan instead of three). All charts update in a single state write, so the heavy Skiko chart
    // recomposition happens once per refresh instead of once per chart.
    fun refresh() {
        val r = repo ?: return
        queryJob?.cancel()
        queryJob = scope.launch {
            launch {
                val rowsResult = r.sampleRows(minAmount, category, country, sort, asc)
                sample = rowsResult.rows
                appendLog(rowsResult.entry)
            }
            launch {
                val dashResult = r.dashboard(minAmount, category, country)
                dashboard = dashResult.value
                appendLog(dashResult.entry)
            }
        }
    }

    fun load(count: Int) {
        val r = repo ?: return
        building = true
        target = count
        progress = 0
        buildPhase = ""
        queryLog = emptyList()
        scope.launch {
            minAmount = 0; category = null; country = null
            genMs = r.generate(count, onProgress = { progress = it }, onPhase = { buildPhase = it })
            rows = count
            building = false
            refresh()
        }
    }

    run {
        Column(Modifier.fillMaxSize().background(SCHEME.background)) {
            TopBar(dataset, onDataset) {
                listOf(100_000 to "100k", 500_000 to "500k", 1_000_000 to "1M").forEach { (n, label) ->
                    Button(onClick = { load(n) }, enabled = repo != null && !building, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Load $label")
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))

            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.widthIn(max = 1120.dp).fillMaxWidth().align(Alignment.TopCenter).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val d = dashboard
                    when {
                        repo == null -> Text("Booting SQLite in your browser…", color = Muted)
                        building -> BuildingBar(progress, target, buildPhase)
                        d != null -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Metric("Latest query", "${queryLog.firstOrNull()?.ms ?: 0} ms", accent = true)
                                Metric("Orders", d.count.grouped())
                                Metric("Revenue", dollars(d.revenue))
                                Metric("Avg order", money(d.avg.roundToInt()))
                            }
                            Text(
                                "${d.count.grouped()} matching of ${rows.toLong().grouped()} rows · " +
                                    "dataset built in ${genMs.grouped()} ms · all in the browser, no server",
                                color = Muted, fontSize = 13.sp,
                            )

                            Filters(minAmount, category, country,
                                onAmount = { minAmount = it }, onAmountDone = { refresh() },
                                onCategory = { category = it; refresh() }, onCountry = { country = it; refresh() })

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.weight(1f)) {
                                    // charty's PieChart, like its LineChart, throws on empty data — guard it
                                    // for the zero-match filter case. (BarChart below is hand-drawn, so it's safe.)
                                    if (d.byCategory.isNotEmpty()) {
                                        PieCard("Revenue by category",
                                            d.byCategory.entries.sortedByDescending { it.value }.map { PieData(it.key, (it.value / 100).toFloat()) })
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    BarChart("Orders by country",
                                        d.byCountry.entries.sortedByDescending { it.value }.map { it.key to it.value }) { it.grouped() }
                                }
                            }
                            // charty's LineChart throws on empty data — guard (e.g. a filter that matches nothing).
                            if (d.byMonth.isNotEmpty()) {
                                LineCard("Revenue by month",
                                    d.byMonth.entries.sortedBy { it.key }.map { LineData(MONTHS.getOrElse(it.key) { "?" }, (it.value / 100).toFloat()) })
                            }

                            RecordsTable(sample, sort, asc) { col ->
                                if (sort == col) asc = !asc else { sort = col; asc = true }
                                refresh()
                            }

                            CodeBlock(
                                "Orders.query()\n    .where(Orders.amount gtEq $minAmount)\n" +
                                    "    .groupBy(Orders.category)\n    .select(Orders.category, Orders.amount.sum())",
                            )

                            QueryLogCard(queryLog.map { LogLine(it.label, it.sql, it.ms) }, onClear = { queryLog = emptyList() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(dataset: Dataset, onDataset: (Dataset) -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(SCHEME.primary))
            Text("Kormium", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("SQL, running in your browser — no server", color = Muted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                Dataset.entries.forEach { d ->
                    FilterChip(selected = dataset == d, onClick = { onDataset(d) }, label = { Text(d.title) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String, accent: Boolean = false) {
    AppCard(Modifier.weight(1f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (accent) SCHEME.primary else SCHEME.onSurface)
        }
    }
}

@Composable
private fun BuildingBar(progress: Int, target: Int, phase: String) {
    // Row inserts fill the bar; the post-insert phases (index builds, ANALYZE) take seconds at
    // 1M rows with no row counter moving — the phase line is what shows the app isn't frozen.
    val inserting = phase.startsWith("Inserting")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Building the dataset in the browser… ${progress.toLong().grouped()} / ${target.toLong().grouped()} rows", color = Muted)
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(SCHEME.surfaceVariant)) {
            val frac = if (target > 0) (progress.toFloat() / target).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SCHEME.primary))
        }
        if (phase.isNotEmpty() && !inserting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(phase, color = Muted, fontSize = 13.sp)
            }
        }
    }
}

private fun logColor(ms: Long): Color = when {
    ms < 100 -> Color(0xFF16A34A)
    ms < 300 -> Color(0xFFD97706)
    else -> Color(0xFFDC2626)
}

/** A hand-drawn scroll thumb — Compose Multiplatform's VerticalScrollbar isn't available on wasmJs. */
@Composable
private fun ScrollIndicator(state: ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(SCHEME.surfaceVariant, RoundedCornerShape(2.dp))) {
        val max = state.maxValue.toFloat()
        if (max > 0f) {
            val viewport = state.viewportSize.toFloat().coerceAtLeast(1f)
            val thumbFraction = (viewport / (max + viewport)).coerceIn(0.08f, 1f)
            val thumbHeight = maxHeight * thumbFraction
            val travel = maxHeight - thumbHeight
            val progress = (state.value / max).coerceIn(0f, 1f)
            Box(
                Modifier
                    .padding(top = travel * progress)
                    .height(thumbHeight)
                    .fillMaxWidth()
                    .background(Muted, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun QueryLogCard(entries: List<LogLine>, onClear: () -> Unit) {
    val scrollState = rememberScrollState()
    // Collapsed by default — it's a "look under the hood" detail, so it lives at the bottom of the
    // page and stays out of the way until the header is clicked.
    var expanded by remember { mutableStateOf(false) }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (expanded) "▾" else "▸", fontSize = 14.sp, color = Muted)
                    Text("Query log — every query behind the numbers above, timed live", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!expanded && entries.isNotEmpty()) {
                        Text("(${entries.size})", fontSize = 13.sp, color = Muted)
                    }
                }
                if (expanded && entries.isNotEmpty()) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Clear")
                    }
                }
            }
            if (expanded) {
                if (entries.isEmpty()) {
                    Text("No queries yet.", color = Muted, fontSize = 13.sp)
                } else {
                    SelectionContainer {
                        Row(Modifier.heightIn(max = 260.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(scrollState)) {
                                entries.forEachIndexed { i, e ->
                                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(e.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("${e.ms} ms", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = logColor(e.ms))
                                        }
                                        Text(e.sql, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                                    }
                                }
                            }
                            ScrollIndicator(scrollState, Modifier.width(4.dp).fillMaxHeight().padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(
    minAmount: Int, category: String?, country: String?,
    onAmount: (Int) -> Unit, onAmountDone: () -> Unit,
    onCategory: (String?) -> Unit, onCountry: (String?) -> Unit,
) {
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Amount ≥ ${money(minAmount)}", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 150.dp))
                Slider(value = minAmount.toFloat(), onValueChange = { onAmount(it.toInt()) }, valueRange = 0f..50_000f, onValueChangeFinished = onAmountDone, modifier = Modifier.weight(1f))
            }
            ChipRow("Category", listOf<String?>(null) + CATEGORIES, category, onCategory)
            ChipRow("Country", listOf<String?>(null) + COUNTRIES, country, onCountry)
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<String?>, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 72.dp), fontSize = 13.sp, color = Muted)
        options.forEach { opt ->
            FilterChip(selected = selected == opt, onClick = { onSelect(opt) }, label = { Text(opt ?: "All") })
        }
    }
}

@Composable
private fun BarChart(title: String, entries: List<Pair<String, Long>>, fmt: (Long) -> String) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val max = entries.maxOfOrNull { it.second } ?: 1L
            entries.forEachIndexed { i, (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.widthIn(min = 56.dp), fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(SCHEME.surfaceVariant)) {
                        Box(
                            Modifier.fillMaxWidth((v.toFloat() / max).coerceIn(0.02f, 1f)).height(14.dp)
                                .clip(RoundedCornerShape(7.dp)).background(PALETTE[i % PALETTE.size]),
                        )
                    }
                    Text(fmt(v), Modifier.widthIn(min = 84.dp), fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun PieCard(title: String, data: List<PieData>) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            PieChart(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                data = { data },
                color = ChartyColor.Gradient(
                    listOf(Color(0xFFE91E63), Color(0xFF2196F3), Color(0xFF4CAF50))
                ),
                config = PieChartConfig(),
            )
        }
    }
}

@Composable
private fun LineCard(title: String, data: List<LineData>) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            LineChart(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                data = { data },
                color = ChartyColor.Gradient(listOf(Color(0xFF4F46E5), Color(0xFF8B5CF6))),
                lineConfig = LineChartConfig(smoothCurve = true, showPoints = true),
            )
        }
    }
}

@Composable
private fun RecordsTable(rows: List<OrderRow>, sort: SortCol, asc: Boolean, onSort: (SortCol) -> Unit) {
    AppCard {
        Column(Modifier.padding(12.dp)) {
            Text("Matching rows — click a header to sort (ORDER BY)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SortHeader("customer", SortCol.CUSTOMER, 2.2f, sort, asc, onSort)
                SortHeader("product", SortCol.PRODUCT, 2.2f, sort, asc, onSort)
                SortHeader("category", SortCol.CATEGORY, 1.4f, sort, asc, onSort)
                SortHeader("country", SortCol.COUNTRY, 0.9f, sort, asc, onSort)
                SortHeader("amount", SortCol.AMOUNT, 1.1f, sort, asc, onSort)
                SortHeader("date", SortCol.DATE, 1.3f, sort, asc, onSort)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
            LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                items(rows) { o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Cell(o.customer, 2.2f); Cell(o.product, 2.2f); Cell(o.category, 1.4f)
                        Cell(o.country, 0.9f); Cell(money(o.amount), 1.1f); Cell(o.date, 1.3f)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SortHeader(label: String, col: SortCol, weight: Float, sort: SortCol, asc: Boolean, onSort: (SortCol) -> Unit) {
    val arrow = if (sort == col) (if (asc) " ▲" else " ▼") else ""
    Text(
        label + arrow,
        Modifier.weight(weight).clickable { onSort(col) },
        fontWeight = FontWeight.Bold, fontSize = 13.sp,
        color = if (sort == col) SCHEME.primary else Muted,
    )
}

@Composable
private fun RowScope.Cell(text: String, weight: Float) = Text(text, Modifier.weight(weight), fontSize = 13.sp)

@Composable
private fun CodeBlock(code: String) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
        Text(code, Modifier.fillMaxWidth().padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE2E8F0))
    }
}

// ---------- Kotlin-ecosystem screen (the headline dataset: 283k real GitHub repos) ----------

@Composable
private fun GithubScreen(dataset: Dataset, onDataset: (Dataset) -> Unit) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<GithubRepository?>(null) }
    var building by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf(0) }
    var genMs by remember { mutableStateOf(0L) }
    var buildPhase by remember { mutableStateOf("") }

    var minStars by remember { mutableStateOf(0) }
    var license by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(RepoStatus.ALL) }
    var minYear by remember { mutableStateOf(YEAR_MIN) }
    var maxYear by remember { mutableStateOf(YEAR_MAX) }
    var sort by remember { mutableStateOf(RepoSort.STARS) }
    var asc by remember { mutableStateOf(false) }

    var dashboard by remember { mutableStateOf<RepoDashboard?>(null) }
    var sample by remember { mutableStateOf<List<RepoRow>>(emptyList()) }
    var topTopics by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var queryJob by remember { mutableStateOf<Job?>(null) }
    var queryLog by remember { mutableStateOf<List<LogLine>>(emptyList()) }

    LaunchedEffect(Unit) { repo = GithubRepository.open() }

    fun appendLog(label: String, sql: String, ms: Long) {
        queryLog = (listOf(LogLine(label, sql, ms)) + queryLog).take(30)
    }

    fun refresh() {
        val r = repo ?: return
        queryJob?.cancel()
        queryJob = scope.launch {
            launch {
                val res = r.sampleRows(minStars, license, status, sort, asc, minYear = minYear, maxYear = maxYear)
                sample = res.rows
                appendLog(res.entry.label, res.entry.sql, res.entry.ms)
            }
            launch {
                val res = r.dashboard(minStars, license, status, minYear, maxYear)
                dashboard = res.value
                appendLog(res.entry.label, res.entry.sql, res.entry.ms)
            }
        }
    }

    fun loadDataset() {
        val r = repo ?: return
        building = true
        progress = 0
        buildPhase = ""
        queryLog = emptyList()
        scope.launch {
            minStars = 0; license = null; status = RepoStatus.ALL
            minYear = YEAR_MIN; maxYear = YEAR_MAX
            genMs = r.load(onProgress = { progress = it }, onPhase = { buildPhase = it })
            rows = progress
            topTopics = r.topTopics
            building = false
            loaded = true
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().background(SCHEME.background)) {
        TopBar(dataset, onDataset) {
            Button(
                onClick = { loadDataset() },
                enabled = repo != null && !building,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(if (loaded) "Reload 283k repos" else "Load 283k repos") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))

        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth().align(Alignment.TopCenter).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val d = dashboard
                when {
                    repo == null -> Text("Booting SQLite in your browser…", color = Muted)
                    building -> BuildingBar(progress, 283_090, buildPhase)
                    !loaded -> GithubIntro(onLoad = ::loadDataset)
                    d != null -> {
                        val abandonedPct = if (d.count > 0) (d.abandoned * 100 / d.count) else 0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metric("Latest query", "${queryLog.firstOrNull()?.ms ?: 0} ms", accent = true)
                            Metric("Repositories", d.count.grouped())
                            Metric("Total stars", d.stars.grouped())
                            Metric("Total forks", d.forks.grouped())
                            Metric("Abandoned", "$abandonedPct%")
                        }
                        Text(
                            "${d.count.grouped()} matching of ${rows.toLong().grouped()} Kotlin repos · " +
                                "avg ${d.avgStars.roundToInt().toLong().grouped()} stars · " +
                                "streamed in + built in ${genMs.grouped()} ms · all in the browser, no server",
                            color = Muted, fontSize = 13.sp,
                        )

                        GithubFilters(minStars, license, status, minYear, maxYear,
                            onStars = { minStars = it }, onStarsDone = { refresh() },
                            onLicense = { license = it; refresh() }, onStatus = { status = it; refresh() },
                            onYears = { lo, hi -> minYear = lo; maxYear = hi }, onYearsDone = { refresh() })

                        if (d.byYear.isNotEmpty()) {
                            LineCard("Repositories created per year — the hockey stick",
                                d.byYear.entries.sortedBy { it.key }.map { LineData(it.key.toString(), it.value.toFloat()) })
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.weight(1f)) {
                                BarChart("Repositories by star count — the iceberg",
                                    d.byBucket.map { it.first to it.second }) { it.grouped() }
                            }
                            Box(Modifier.weight(1f)) {
                                if (d.byLicense.isNotEmpty()) {
                                    PieCard("Repositories by license",
                                        d.byLicense.entries.sortedByDescending { it.value }.take(6).map { PieData(it.key, it.value.toFloat()) })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.weight(1f)) {
                                BarChart("Top owners (matching repos)",
                                    d.topOwners.map { it.first to it.second }) { it.grouped() }
                            }
                            Box(Modifier.weight(1f)) {
                                if (topTopics.isNotEmpty()) {
                                    BarChart("Most common topics (whole ecosystem)",
                                        topTopics.take(10).map { it.first to it.second }) { it.grouped() }
                                }
                            }
                        }
                        BarChart("Total stars by creation year",
                            d.starsByYear.entries.sortedBy { it.key }.map { it.key.toString() to it.value }) { it.grouped() }

                        repo?.ecosystem?.let { eco -> EcosystemSection(eco) }

                        GithubTable(sample, sort, asc) { col ->
                            if (sort == col) asc = !asc else { sort = col; asc = true }
                            refresh()
                        }

                        CodeBlock(
                            "Repos.query()\n    .where(Repos.stars gtEq $minStars)\n" +
                                "    .groupBy(Repos.createdYear, Repos.license)\n" +
                                "    .select(Repos.createdYear, Repos.license, count(), Repos.stars.sum(), Repos.forks.sum())",
                        )

                        QueryLogCard(queryLog, onClear = { queryLog = emptyList() })
                    }
                }
            }
        }
    }
}

@Composable
private fun GithubIntro(onLoad: () -> Unit) {
    AppCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Every Kotlin repository on GitHub", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "283,090 repositories with language:kotlin and at least one star, crawled from the " +
                    "GitHub API by a Kotlin + Kormium program — then streamed into SQLite in this tab " +
                    "and queried with the same type-safe Kotlin DSL a server would use. " +
                    "Loading downloads a ~9 MB feed and rebuilds the database in your browser.",
                color = Muted, fontSize = 14.sp,
            )
            Button(onClick = onLoad, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Load 283k repos")
            }
        }
    }
}

@Composable
private fun GithubFilters(
    minStars: Int, license: String?, status: RepoStatus, minYear: Int, maxYear: Int,
    onStars: (Int) -> Unit, onStarsDone: () -> Unit,
    onLicense: (String?) -> Unit, onStatus: (RepoStatus) -> Unit,
    onYears: (Int, Int) -> Unit, onYearsDone: () -> Unit,
) {
    AppCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Stars ≥ ${minStars.toLong().grouped()}", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 150.dp))
                Slider(value = minStars.toFloat(), onValueChange = { onStars(it.toInt()) }, valueRange = 0f..2_000f, onValueChangeFinished = onStarsDone, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Created $minYear–$maxYear", fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 150.dp))
                RangeSlider(
                    value = minYear.toFloat()..maxYear.toFloat(),
                    onValueChange = { onYears(it.start.roundToInt(), it.endInclusive.roundToInt()) },
                    valueRange = YEAR_MIN.toFloat()..YEAR_MAX.toFloat(),
                    steps = (YEAR_MAX - YEAR_MIN - 1).coerceAtLeast(0),
                    onValueChangeFinished = onYearsDone,
                    modifier = Modifier.weight(1f),
                )
            }
            ChipRow("License", listOf<String?>(null) + LICENSES, license, onLicense)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Status", Modifier.widthIn(min = 72.dp), fontSize = 13.sp, color = Muted)
                listOf(RepoStatus.ALL to "All", RepoStatus.ACTIVE to "Active", RepoStatus.ABANDONED to "Abandoned (no push 2y)").forEach { (s, label) ->
                    FilterChip(selected = status == s, onClick = { onStatus(s) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun GithubTable(rows: List<RepoRow>, sort: RepoSort, asc: Boolean, onSort: (RepoSort) -> Unit) {
    AppCard {
        Column(Modifier.padding(12.dp)) {
            Text("Matching repositories — click a header to sort (ORDER BY)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                RepoSortHeader("repository", RepoSort.NAME, 2.6f, sort, asc, onSort)
                Text("topics", Modifier.weight(2.4f), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Muted)
                RepoSortHeader("stars", RepoSort.STARS, 1.0f, sort, asc, onSort)
                Text("forks", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Muted)
                Text("license", Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Muted)
                RepoSortHeader("created", RepoSort.CREATED, 1.1f, sort, asc, onSort)
                RepoSortHeader("last push", RepoSort.PUSHED, 1.1f, sort, asc, onSort)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(rows) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(
                            "${r.owner}/${r.name}",
                            Modifier.weight(2.6f).clickable { openUrl("https://github.com/${r.owner}/${r.name}") },
                            fontSize = 13.sp,
                            color = SCHEME.primary,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 1,
                        )
                        Text(r.topics.replace(",", ", ").ifEmpty { "—" }, Modifier.weight(2.4f), fontSize = 12.sp, color = Muted, maxLines = 1)
                        Cell("★ ${r.stars.toLong().grouped()}", 1.0f)
                        Cell("⑂ ${r.forks.toLong().grouped()}", 0.9f)
                        Cell(r.license.ifEmpty { "—" }, 1.2f)
                        Cell(r.createdAt, 1.1f)
                        Cell(r.pushedAt.ifEmpty { "—" }, 1.1f)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.RepoSortHeader(label: String, col: RepoSort, weight: Float, sort: RepoSort, asc: Boolean, onSort: (RepoSort) -> Unit) {
    val arrow = if (sort == col) (if (asc) " ▲" else " ▼") else ""
    Text(
        label + arrow,
        Modifier.weight(weight).clickable { onSort(col) },
        fontWeight = FontWeight.Bold, fontSize = 13.sp,
        color = if (sort == col) SCHEME.primary else Muted,
    )
}

// ---------- reusable trend visuals (hand-drawn on Canvas for full control) ----------

/** One line on a MultiLineCard. [points] are y-values aligned to the shared x axis (evenly spaced). */
data class LineSeries(val name: String, val color: Color, val points: List<Float>, val dashed: Boolean = false)

/**
 * A multi-series line chart with a legend and sparse x labels. y runs 0..[yMax] (auto if null);
 * text is drawn outside the Canvas (Compose/wasm has no easy in-canvas text), so the Canvas holds
 * only gridlines and the lines themselves.
 */
@Composable
private fun MultiLineCard(
    title: String,
    subtitle: String,
    xLabels: List<String>,
    series: List<LineSeries>,
    yMax: Float? = null,
    yTopLabel: String = "",
) {
    // Named series can be toggled on/off from the legend; series without a name (single-line
    // charts, reference curves) are always drawn. Hidden names are tracked by string so the set
    // survives recomposition without depending on series identity.
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val named = series.filter { it.name.isNotEmpty() }
    val toggleable = named.size > 1
    val shown = series.filter { it.name.isEmpty() || it.name !in hidden }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp)
            if (named.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    named.forEach { s ->
                        val on = s.name !in hidden
                        val rowModifier = if (toggleable) {
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { hidden = if (on) hidden + s.name else hidden - s.name }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        } else {
                            Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        }
                        Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) s.color else CardBorder))
                            Text(s.name, fontSize = 12.sp, color = if (on) SCHEME.onSurface else Muted)
                        }
                    }
                }
            }
            val maxY = yMax ?: (shown.flatMap { it.points }.maxOrNull()?.takeIf { it > 0f } ?: 1f)
            if (yTopLabel.isNotEmpty()) Text(yTopLabel, color = Muted, fontSize = 11.sp)
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                val w = size.width
                val h = size.height
                // faint gridlines at 0 / 50% / 100% of maxY
                for (g in 0..2) {
                    val y = h * g / 2f
                    drawLine(CardBorder, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                shown.forEach { s ->
                    val pts = s.points
                    if (pts.size < 2) return@forEach
                    val path = Path()
                    pts.forEachIndexed { i, v ->
                        val x = w * i / (pts.size - 1)
                        val y = h - (v / maxY).coerceIn(0f, 1f) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    val effect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                    drawPath(path, s.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round, pathEffect = effect))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val step = (xLabels.size / 8).coerceAtLeast(1)
                xLabels.forEachIndexed { i, label ->
                    Text(if (i % step == 0) label else "", fontSize = 10.sp, color = Muted)
                }
            }
        }
    }
}

/**
 * Horizontal stacked bars: each bar's width is proportional to its total (so volume is visible),
 * split into an [activeColor] and a muted remainder — the growing grey share on older years reads
 * as abandonment at a glance.
 */
@Composable
private fun StackedBarCard(title: String, subtitle: String, rows: List<Triple<String, Long, Long>>) {
    val activeColor = Color(0xFF22C55E)
    val deadColor = Color(0xFFCBD5E1)
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(activeColor)); Text("active", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(deadColor)); Text("abandoned", fontSize = 12.sp)
                }
            }
            val maxTotal = rows.maxOfOrNull { it.second + it.third } ?: 1L
            rows.forEach { (label, active, dead) ->
                val total = active + dead
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.widthIn(min = 40.dp), fontSize = 12.sp)
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Row(Modifier.fillMaxWidth(if (maxTotal > 0) (total.toFloat() / maxTotal) else 0f).height(14.dp).clip(RoundedCornerShape(7.dp))) {
                            if (active > 0) Box(Modifier.weight(active.toFloat()).fillMaxHeight().background(activeColor))
                            if (dead > 0) Box(Modifier.weight(dead.toFloat()).fillMaxHeight().background(deadColor))
                        }
                    }
                    val pctActive = if (total > 0) (active * 100 / total).toInt() else 0
                    Text("${total.grouped()}  ($pctActive% live)", Modifier.widthIn(min = 120.dp), fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
}

/**
 * The ecosystem-over-time section: five structural trend charts computed once at load (not
 * filtered), the ones that carry the "how has Kotlin's ecosystem evolved" story for a write-up.
 */
@Composable
private fun EcosystemSection(eco: EcosystemStats) {
    val years = eco.years
    val xLabels = years.map { it.toString() }
    fun pointsFor(pairs: List<Pair<Int, Int>>): List<Float> {
        val m = pairs.toMap()
        return years.map { (m[it] ?: 0).toFloat() }
    }

    Text("The Kotlin ecosystem over time", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SCHEME.onSurface)
    Text(
        "Structural trends across all ${eco.cumulativeByYear.lastOrNull()?.second?.grouped() ?: ""} repositories — " +
            "computed once while the dataset streamed in, independent of the filters above.",
        color = Muted, fontSize = 13.sp,
    )

    // 1. Topic families' share of each year's new repos.
    MultiLineCard(
        title = "Topic trends — share of each year's new repos",
        subtitle = "% of repos created that year tagged with each topic family (aliases merged)",
        xLabels = xLabels,
        series = eco.topicTrends.mapIndexed { i, t ->
            LineSeries(t.topic, PALETTE[i % PALETTE.size], pointsFor(t.sharePctByYear))
        },
        yTopLabel = "% of that year's repos",
    )

    // 2. Licensing + survival by cohort year (both 0..100%).
    MultiLineCard(
        title = "Health by cohort year — licensing & survival",
        subtitle = "Of the repos created each year: % that carry a license, and % still pushed within the last 2 years",
        xLabels = xLabels,
        series = listOf(
            LineSeries("Has a license", PALETTE[0], pointsFor(eco.licensedPctByYear)),
            LineSeries("Still active", PALETTE[1], pointsFor(eco.activePctByYear)),
        ),
        yMax = 100f,
        yTopLabel = "100%",
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // 3. Cumulative repositories in existence.
        Box(Modifier.weight(1f)) {
            val cum = eco.cumulativeByYear.toMap()
            MultiLineCard(
                title = "Cumulative Kotlin repositories",
                subtitle = "total repos in existence, year over year",
                xLabels = xLabels,
                series = listOf(LineSeries("", SCHEME.primary, years.map { (cum[it] ?: 0L).toFloat() })),
                yTopLabel = eco.cumulativeByYear.lastOrNull()?.second?.grouped() ?: "",
            )
        }
        // 6. Star inequality: the Lorenz curve.
        Box(Modifier.weight(1f)) {
            MultiLineCard(
                title = "Star inequality — Lorenz curve",
                subtitle = "top 1% of repos hold ${eco.top1PctStarShare}% of all stars · top 10% hold ${eco.top10PctStarShare}%",
                xLabels = listOf("poorest", "", "", "", "richest"),
                series = listOf(
                    LineSeries("equality", CardBorder, listOf(0f, 1f), dashed = true),
                    LineSeries("stars", PALETTE[3], eco.lorenz.map { it.second }),
                ),
                yMax = 1f,
                yTopLabel = "100% of stars",
            )
        }
    }

    // 5. Active vs abandoned by creation year — volume and decay in one.
    StackedBarCard(
        title = "Active vs abandoned by creation year",
        subtitle = "bar width = repos created that year; green = still pushed within 2 years",
        rows = eco.activeAbandonedByYear.map { Triple(it.first.toString(), it.second, it.third) },
    )
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.kormium.sample.sqldemo

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private fun Long.grouped(): String = toString().reversed().chunked(3).joinToString(",").reversed()
private fun money(cents: Int): String = "$${cents / 100}.${pad2(cents % 100)}"
private fun dollars(cents: Long): String = "$" + (cents / 100).grouped()

@Composable
fun App() {
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

    MaterialTheme(colorScheme = SCHEME) {
        Column(Modifier.fillMaxSize().background(SCHEME.background)) {
            TopBar(enabled = repo != null && !building, onLoad = ::load)
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

                            QueryLogCard(queryLog, onClear = { queryLog = emptyList() })

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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(enabled: Boolean, onLoad: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(SCHEME.primary))
            Text("Kormium", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("SQL, running in your browser — no server", color = Muted, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100_000 to "100k", 500_000 to "500k", 1_000_000 to "1M").forEach { (n, label) ->
                Button(onClick = { onLoad(n) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Load $label")
                }
            }
        }
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
private fun QueryLogCard(entries: List<QueryLogEntry>, onClear: () -> Unit) {
    val scrollState = rememberScrollState()
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Query log — every query behind the numbers above, timed live", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Clear")
                    }
                }
            }
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

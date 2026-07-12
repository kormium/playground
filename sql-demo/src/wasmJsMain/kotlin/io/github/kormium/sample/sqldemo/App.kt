@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.kormium.sample.sqldemo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
import kotlinx.browser.window
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
private val PrimaryHover = Color(0xFF4338CA) // primary, darkened for button hover
private val ChipHover = Color(0xFFF1F5F9)     // subtle grey fill for chip hover

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

/**
 * A click target with no hover/press overlay. The default [clickable] indication draws a
 * rectangular highlight that ignores our rounded clips, so on hover a mismatched box appeared
 * behind the label — this drops the indication entirely for our flat, mockup-style controls.
 */
@Composable
private fun Modifier.tapClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/**
 * A no-op indication. Provided as [LocalIndication] so Material components that read the ambient
 * indication (rather than their own ripple) draw no hover/press/focus state layer either — on
 * Wasm/Skiko that state layer isn't clipped to the control's shape and showed as a rectangle.
 */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}

/**
 * Flat, custom-drawn replacements for Material's Button/FilterChip. Material's per-content hover
 * state layer renders as an unclipped rectangle behind the label on Wasm/Skiko; these are built on
 * [tapClickable] so they have no state layer at all — the whole control is one drawn shape.
 */
@Composable
private fun PillButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        !enabled -> SCHEME.primary.copy(alpha = 0.4f)
        hovered -> PrimaryHover
        else -> SCHEME.primary
    }
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> if (hovered) PrimaryHover else SCHEME.primary
        hovered -> ChipHover
        else -> Color.White
    }
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .border(1.dp, if (selected) bg else CardBorder, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) Color.White else SCHEME.onSurface, fontSize = 13.sp)
    }
}

// ---------- hand-drawn line icons (Compose/wasm's default font has no emoji glyphs) ----------

private enum class Ico { BOLT, DB, STAR, FORK, GHOST, RECEIPT, MONEY, BARS, GRID, SEARCH, TREND, SCALE, API, INFO }

@Composable
private fun Ico(kind: Ico, tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            drawCircle(tint, radius = r * s, center = p(cx, cy), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        fun poly(vararg xy: Float, fill: Boolean = true, close: Boolean = true) {
            val path = Path()
            var i = 0
            while (i < xy.size) { val o = p(xy[i], xy[i + 1]); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y); i += 2 }
            if (close) path.close()
            drawPath(path, tint, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        }
        when (kind) {
            Ico.BOLT -> poly(0.58f, 0.06f, 0.24f, 0.56f, 0.46f, 0.56f, 0.4f, 0.94f, 0.78f, 0.42f, 0.54f, 0.42f)
            Ico.DB -> {
                drawOval(tint, topLeft = p(0.18f, 0.1f), size = Size(s * 0.64f, s * 0.2f), style = stroke)
                line(0.18f, 0.2f, 0.18f, 0.8f); line(0.82f, 0.2f, 0.82f, 0.8f)
                drawArc(tint, 0f, 180f, false, topLeft = p(0.18f, 0.4f), size = Size(s * 0.64f, s * 0.2f), style = stroke)
                drawArc(tint, 0f, 180f, false, topLeft = p(0.18f, 0.7f), size = Size(s * 0.64f, s * 0.2f), style = stroke)
            }
            Ico.STAR -> {
                val path = Path()
                val cx = 0.5f; val cy = 0.52f; val ro = 0.44f; val ri = 0.18f
                for (k in 0..10) {
                    val r = if (k % 2 == 0) ro else ri
                    val a = -PI / 2 + k * PI / 5
                    val o = p(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
                    if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                path.close(); drawPath(path, tint)
            }
            Ico.FORK -> {
                circle(0.28f, 0.22f, 0.11f); circle(0.72f, 0.22f, 0.11f); circle(0.5f, 0.8f, 0.11f)
                line(0.28f, 0.33f, 0.28f, 0.46f); line(0.72f, 0.33f, 0.72f, 0.46f)
                line(0.28f, 0.46f, 0.72f, 0.46f); line(0.5f, 0.46f, 0.5f, 0.69f)
            }
            Ico.GHOST -> {
                val path = Path()
                path.moveTo(p(0.2f, 0.95f).x, p(0.2f, 0.95f).y)
                path.lineTo(p(0.2f, 0.48f).x, p(0.2f, 0.48f).y)
                path.cubicTo(p(0.2f, 0.12f).x, p(0.2f, 0.12f).y, p(0.8f, 0.12f).x, p(0.8f, 0.12f).y, p(0.8f, 0.48f).x, p(0.8f, 0.48f).y)
                path.lineTo(p(0.8f, 0.95f).x, p(0.8f, 0.95f).y)
                path.lineTo(p(0.65f, 0.82f).x, p(0.65f, 0.82f).y)
                path.lineTo(p(0.5f, 0.95f).x, p(0.5f, 0.95f).y)
                path.lineTo(p(0.35f, 0.82f).x, p(0.35f, 0.82f).y)
                path.close(); drawPath(path, tint, style = stroke)
                circle(0.4f, 0.45f, 0.05f, fill = true); circle(0.6f, 0.45f, 0.05f, fill = true)
            }
            Ico.RECEIPT -> {
                poly(0.25f, 0.08f, 0.75f, 0.08f, 0.75f, 0.92f, 0.63f, 0.82f, 0.5f, 0.92f, 0.37f, 0.82f, 0.25f, 0.92f, fill = false)
                line(0.36f, 0.32f, 0.64f, 0.32f); line(0.36f, 0.5f, 0.64f, 0.5f)
            }
            Ico.MONEY -> { circle(0.5f, 0.5f, 0.42f); line(0.5f, 0.24f, 0.5f, 0.76f); line(0.62f, 0.36f, 0.42f, 0.36f); line(0.38f, 0.64f, 0.58f, 0.64f) }
            Ico.BARS -> { line(0.28f, 0.9f, 0.28f, 0.55f); line(0.5f, 0.9f, 0.5f, 0.3f); line(0.72f, 0.9f, 0.72f, 0.45f) }
            Ico.GRID -> {
                drawRect(tint, topLeft = p(0.16f, 0.16f), size = Size(s * 0.28f, s * 0.28f), style = stroke)
                drawRect(tint, topLeft = p(0.56f, 0.16f), size = Size(s * 0.28f, s * 0.28f), style = stroke)
                drawRect(tint, topLeft = p(0.16f, 0.56f), size = Size(s * 0.28f, s * 0.28f), style = stroke)
                drawRect(tint, topLeft = p(0.56f, 0.56f), size = Size(s * 0.28f, s * 0.28f), style = stroke)
            }
            Ico.SEARCH -> { circle(0.42f, 0.42f, 0.28f); line(0.63f, 0.63f, 0.86f, 0.86f) }
            Ico.TREND -> { poly(0.12f, 0.72f, 0.4f, 0.44f, 0.56f, 0.6f, 0.88f, 0.24f, fill = false, close = false); poly(0.66f, 0.24f, 0.88f, 0.24f, 0.88f, 0.46f, fill = false, close = false) }
            Ico.SCALE -> { line(0.5f, 0.14f, 0.5f, 0.86f); line(0.22f, 0.28f, 0.78f, 0.28f); line(0.32f, 0.86f, 0.68f, 0.86f); drawArc(tint, 0f, 180f, false, topLeft = p(0.12f, 0.28f), size = Size(s * 0.2f, s * 0.2f), style = stroke); drawArc(tint, 0f, 180f, false, topLeft = p(0.68f, 0.28f), size = Size(s * 0.2f, s * 0.2f), style = stroke) }
            Ico.API -> { poly(0.34f, 0.28f, 0.16f, 0.5f, 0.34f, 0.72f, fill = false, close = false); poly(0.66f, 0.28f, 0.84f, 0.5f, 0.66f, 0.72f, fill = false, close = false); line(0.56f, 0.24f, 0.44f, 0.76f) }
            Ico.INFO -> { circle(0.5f, 0.5f, 0.42f); circle(0.5f, 0.32f, 0.03f, fill = true); line(0.5f, 0.46f, 0.5f, 0.7f) }
        }
    }
}

private fun Long.grouped(): String = toString().reversed().chunked(3).joinToString(",").reversed()
private fun money(cents: Int): String = "$${cents / 100}.${pad2(cents % 100)}"
private fun dollars(cents: Long): String = "$" + (cents / 100).grouped()

/** The published Kormium version this demo builds against (see build.gradle.kts dependencies). */
private const val KORMIUM_VERSION = "0.10.0"
private const val KORMIUM_REPO_URL = "https://github.com/kormium/kormium"
private const val KORMIUM_DOCS_URL = "https://github.com/kormium/kormium/blob/main/docs/README.md"

/** Everything the sidebar can navigate to: the two live dashboards and the static doc pages. */
private enum class Page(val label: String, val slug: String, val icon: Ico, val isDoc: Boolean) {
    GITHUB("Kotlin ecosystem", "ecosystem", Ico.DB, false),
    SALES("Synthetic sales", "sales", Ico.BARS, false),
    QUICK_START("Quick start", "quick-start", Ico.BOLT, true),
    INSTALLATION("Installation", "installation", Ico.GRID, true),
    BENCHMARKS("Benchmarks", "benchmarks", Ico.TREND, true),
}

/** The page named by the current URL hash (e.g. `#/quick-start`), defaulting to the GitHub dashboard. */
private fun pageFromHash(): Page {
    val slug = window.location.hash.removePrefix("#").trim('/')
    return Page.entries.firstOrNull { it.slug == slug } ?: Page.GITHUB
}

/** True on narrow (phone-width) viewports; drives the layout switches throughout the app. */
private val LocalCompact = staticCompositionLocalOf { false }

@Composable
fun App() {
    // The URL hash is the source of truth for the current page, so deep links, refreshes and the
    // browser's back/forward buttons all work. Navigation writes the hash; the listener reads it.
    var page by remember { mutableStateOf(pageFromHash()) }
    DisposableEffect(Unit) {
        window.onhashchange = { page = pageFromHash() }
        onDispose { window.onhashchange = null }
    }
    fun navigate(p: Page) {
        page = p
        window.location.hash = "/${p.slug}"
    }
    MaterialTheme(colorScheme = SCHEME) {
        BoxWithConstraints(Modifier.fillMaxSize().background(SCHEME.background)) {
            val compact = maxWidth < 720.dp
            // Disable Material3's ripple/state layers: on Wasm/Skiko the hover state layer isn't
            // clipped to the component shape, so it painted a rectangle behind chip/button labels.
            CompositionLocalProvider(
                LocalCompact provides compact,
                LocalRippleConfiguration provides null,
                LocalIndication provides NoIndication,
            ) {
                if (compact) {
                    // Phone: the rail collapses into a slim top header with a scrollable pill nav.
                    Column(Modifier.fillMaxSize()) {
                        CompactNav(page) { navigate(it) }
                        Box(Modifier.weight(1f).fillMaxWidth()) { PageContent(page) }
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(page) { navigate(it) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { PageContent(page) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    when (page) {
        Page.GITHUB -> GithubScreen()
        Page.SALES -> SalesScreen()
        else -> DocScreen(page)
    }
}

/** Phone-width navigation: a dark header with the clickable logo and a horizontally scrollable pill row. */
@Composable
private fun CompactNav(current: Page, onNavigate: (Page) -> Unit) {
    Column(Modifier.fillMaxWidth().background(SidebarBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openUrl(KORMIUM_REPO_URL) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(SCHEME.primary), contentAlignment = Alignment.Center) {
                    Text("K", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text("Kormium", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Text("v$KORMIUM_VERSION", color = SidebarMuted, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Page.entries.forEach { p -> NavPill(p.label, current == p) { onNavigate(p) } }
            NavPill("Docs ↗", false) { openUrl(KORMIUM_DOCS_URL) }
        }
    }
}

@Composable
private fun NavPill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (active) SCHEME.primary else SidebarActive)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (active) Color.White else SidebarMuted, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Lay metric cards in rows of [perRow], padding the final row so all cards keep equal width. */
@Composable
private fun MetricGrid(perRow: Int, metrics: List<@Composable RowScope.() -> Unit>) {
    metrics.chunked(perRow).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rowItems.forEach { it() }
            repeat(perRow - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Two cards side by side on wide viewports, stacked on compact ones. */
@Composable
private fun Pair2(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    if (LocalCompact.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { a(); b() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f)) { a() }
            Box(Modifier.weight(1f)) { b() }
        }
    }
}

// The dark navigation rail — the app's primary navigation between the two dashboards and the docs.
private val SidebarBg = Color(0xFF0B1220)
private val SidebarCard = Color(0xFF111C2E)
private val SidebarActive = Color(0xFF1E293B)
private val SidebarMuted = Color(0xFF94A3B8)
private val SidebarFaint = Color(0xFF64748B)

@Composable
private fun Sidebar(current: Page, onNavigate: (Page) -> Unit) {
    Column(Modifier.width(220.dp).fillMaxHeight().background(SidebarBg).padding(16.dp)) {
        Row(
            Modifier.padding(top = 4.dp, bottom = 20.dp).clip(RoundedCornerShape(8.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openUrl(KORMIUM_REPO_URL) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(SCHEME.primary), contentAlignment = Alignment.Center) {
                Text("K", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text("Kormium", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SidebarLabel("Dashboards")
            Page.entries.filter { !it.isDoc }.forEach { p ->
                SidebarItem(p.icon, p.label, current == p) { onNavigate(p) }
            }
            SidebarLabel("Docs")
            Page.entries.filter { it.isDoc }.forEach { p ->
                SidebarItem(p.icon, p.label, current == p) { onNavigate(p) }
            }
            SidebarItem(Ico.API, "Docs ↗", active = false) { openUrl(KORMIUM_DOCS_URL) }
        }
        Box(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SidebarCard).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                Text("Live database", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("283,090 repositories", color = SidebarMuted, fontSize = 11.sp)
            Text("Updated just now", color = SidebarFaint, fontSize = 11.sp)
        }
        Text("Built with Kormium v$KORMIUM_VERSION", color = SidebarMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
        Text("Apache-2.0", color = SidebarFaint, fontSize = 11.sp)
    }
}

@Composable
private fun SidebarLabel(text: String) {
    Text(
        text.uppercase(), color = SidebarFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 12.dp),
    )
}

@Composable
private fun SidebarItem(icon: Ico, label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg = if (active || hovered) Color.White else SidebarMuted
    val bg = when {
        active -> SidebarActive
        hovered -> SidebarActive.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ico(icon, fg, size = 18.dp)
        Text(label, color = fg, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SalesScreen() {
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

    val compact = LocalCompact.current
    run {
        Column(Modifier.fillMaxSize().background(SCHEME.background)) {
            TopBar {
                listOf(100_000 to "100k", 500_000 to "500k", 1_000_000 to "1M").forEach { (n, label) ->
                    PillButton("Load $label", enabled = repo != null && !building) { load(n) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))

            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.widthIn(max = 1120.dp).fillMaxWidth().align(Alignment.TopCenter).padding(if (compact) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val d = dashboard
                    when {
                        repo == null -> Text("Booting SQLite in your browser…", color = Muted)
                        building -> BuildingBar(progress, target, buildPhase)
                        d != null -> {
                            MetricGrid(if (compact) 2 else 4, listOf(
                                { Metric("Latest query", "${queryLog.firstOrNull()?.ms ?: 0} ms", icon = Ico.BOLT, sub = "SQL timed live", tint = SCHEME.primary, accent = true) },
                                { Metric("Orders", d.count.grouped(), icon = Ico.RECEIPT, sub = "matching rows", tint = Color(0xFFF59E0B)) },
                                { Metric("Revenue", dollars(d.revenue), icon = Ico.MONEY, sub = "sum(amount)", tint = Color(0xFF22C55E)) },
                                { Metric("Avg order", money(d.avg.roundToInt()), icon = Ico.BARS, sub = "per order", tint = Color(0xFF06B6D4)) },
                            ))
                            Text(
                                "${d.count.grouped()} matching of ${rows.toLong().grouped()} rows · " +
                                    "dataset built in ${genMs.grouped()} ms · all in the browser, no server",
                                color = Muted, fontSize = 13.sp,
                            )

                            Filters(minAmount, category, country,
                                onAmount = { minAmount = it }, onAmountDone = { refresh() },
                                onCategory = { category = it; refresh() }, onCountry = { country = it; refresh() })

                            Pair2(
                                {
                                    // charty's PieChart, like its LineChart, throws on empty data — guard it
                                    // for the zero-match filter case. (BarChart below is hand-drawn, so it's safe.)
                                    if (d.byCategory.isNotEmpty()) {
                                        PieCard("Revenue by category",
                                            d.byCategory.entries.sortedByDescending { it.value }.map { PieData(it.key, (it.value / 100).toFloat()) })
                                    }
                                },
                                {
                                    BarChart("Orders by country",
                                        d.byCountry.entries.sortedByDescending { it.value }.map { it.key to it.value }) { it.grouped() }
                                },
                            )
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
private fun TopBar(
    title: String = "Kormium Playground",
    subtitle: String = "SQL, running in your browser — no server",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val compact = LocalCompact.current
    if (compact) {
        // Phone: title over a horizontally scrollable action row, so wide button sets never clip.
        Column(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Muted, fontSize = 13.sp)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(subtitle, color = Muted, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun RowScope.Metric(
    label: String, value: String,
    icon: Ico? = null, sub: String = "", tint: Color = SCHEME.primary, accent: Boolean = false,
) {
    AppCard(Modifier.weight(1f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Ico(icon, tint, size = 20.dp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = Muted, fontSize = 12.sp)
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (accent) SCHEME.primary else SCHEME.onSurface)
                if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 11.sp)
            }
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).tapClickable { expanded = !expanded },
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
                    Text(
                        "Clear",
                        Modifier.clip(RoundedCornerShape(6.dp)).tapClickable(onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                        color = SCHEME.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
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
            Chip(opt ?: "All", selected == opt) { onSelect(opt) }
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
    val compact = LocalCompact.current
    AppCard {
        Column(Modifier.padding(12.dp)) {
            Text("Matching rows — click a header to sort (ORDER BY)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            // On phones the six columns don't fit; give the table a readable min width and scroll it sideways.
            Box(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
                Column(if (compact) Modifier.width(680.dp) else Modifier.fillMaxWidth()) {
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
    }
}

@Composable
private fun RowScope.SortHeader(label: String, col: SortCol, weight: Float, sort: SortCol, asc: Boolean, onSort: (SortCol) -> Unit) {
    val arrow = if (sort == col) (if (asc) " ▲" else " ▼") else ""
    Text(
        label + arrow,
        Modifier.weight(weight).tapClickable { onSort(col) },
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
private fun GithubScreen() {
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

    val compact = LocalCompact.current
    Column(Modifier.fillMaxSize().background(SCHEME.background)) {
        TopBar {
            PillButton(if (loaded) "Reload 283k repos" else "Load 283k repos", enabled = repo != null && !building) { loadDataset() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))

        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth().align(Alignment.TopCenter).padding(if (compact) 12.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val d = dashboard
                when {
                    repo == null -> Text("Booting SQLite in your browser…", color = Muted)
                    building -> BuildingBar(progress, 283_090, buildPhase)
                    !loaded -> GithubIntro(onLoad = ::loadDataset)
                    d != null -> {
                        val abandonedPct = if (d.count > 0) (d.abandoned * 100 / d.count) else 0
                        MetricGrid(if (compact) 2 else 5, listOf(
                            { Metric("Latest query", "${queryLog.firstOrNull()?.ms ?: 0} ms", icon = Ico.BOLT, sub = "avg ${d.avgStars.roundToInt()} stars", tint = SCHEME.primary, accent = true) },
                            { Metric("Repositories", d.count.grouped(), icon = Ico.DB, sub = "matching repos", tint = Color(0xFFF59E0B)) },
                            { Metric("Total stars", d.stars.grouped(), icon = Ico.STAR, sub = "across all repos", tint = Color(0xFFF59E0B)) },
                            { Metric("Total forks", d.forks.grouped(), icon = Ico.FORK, sub = "all repositories", tint = Color(0xFF06B6D4)) },
                            { Metric("Abandoned", "$abandonedPct%", icon = Ico.GHOST, sub = "no push in 2y", tint = Color(0xFFEC4899)) },
                        ))
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

                        Pair2(
                            {
                                BarChart("Repositories by star count — the iceberg",
                                    d.byBucket.map { it.first to it.second }) { it.grouped() }
                            },
                            {
                                if (d.byLicense.isNotEmpty()) {
                                    PieCard("Repositories by license",
                                        d.byLicense.entries.sortedByDescending { it.value }.take(6).map { PieData(it.key, it.value.toFloat()) })
                                }
                            },
                        )
                        Pair2(
                            {
                                BarChart("Top owners (matching repos)",
                                    d.topOwners.map { it.first to it.second }) { it.grouped() }
                            },
                            {
                                if (topTopics.isNotEmpty()) {
                                    BarChart("Most common topics (whole ecosystem)",
                                        topTopics.take(10).map { it.first to it.second }) { it.grouped() }
                                }
                            },
                        )
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
            PillButton("Load 283k repos", onClick = onLoad)
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
                    Chip(label, status == s) { onStatus(s) }
                }
            }
        }
    }
}

@Composable
private fun GithubTable(rows: List<RepoRow>, sort: RepoSort, asc: Boolean, onSort: (RepoSort) -> Unit) {
    val compact = LocalCompact.current
    AppCard {
        Column(Modifier.padding(12.dp)) {
            Text("Matching repositories — click a header to sort (ORDER BY)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            // On phones the seven columns don't fit; give the table a readable min width and scroll it sideways.
            Box(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
                Column(if (compact) Modifier.width(820.dp) else Modifier.fillMaxWidth()) {
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
                                    Modifier.weight(2.6f).tapClickable { openUrl("https://github.com/${r.owner}/${r.name}") },
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
    }
}

@Composable
private fun RowScope.RepoSortHeader(label: String, col: RepoSort, weight: Float, sort: RepoSort, asc: Boolean, onSort: (RepoSort) -> Unit) {
    val arrow = if (sort == col) (if (asc) " ▲" else " ▼") else ""
    Text(
        label + arrow,
        Modifier.weight(weight).tapClickable { onSort(col) },
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
                                .tapClickable { hidden = if (on) hidden + s.name else hidden - s.name }
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

    Pair2(
        {
            // 3. Cumulative repositories in existence.
            val cum = eco.cumulativeByYear.toMap()
            MultiLineCard(
                title = "Cumulative Kotlin repositories",
                subtitle = "total repos in existence, year over year",
                xLabels = xLabels,
                series = listOf(LineSeries("", SCHEME.primary, years.map { (cum[it] ?: 0L).toFloat() })),
                yTopLabel = eco.cumulativeByYear.lastOrNull()?.second?.grouped() ?: "",
            )
        },
        {
            // 6. Star inequality: the Lorenz curve.
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
        },
    )

    // 5. Active vs abandoned by creation year — volume and decay in one.
    StackedBarCard(
        title = "Active vs abandoned by creation year",
        subtitle = "bar width = repos created that year; green = still pushed within 2 years",
        rows = eco.activeAbandonedByYear.map { Triple(it.first.toString(), it.second, it.third) },
    )
}

// ---------- doc pages (a tiny Markdown renderer over the real Kormium docs) ----------

@Composable
private fun DocScreen(page: Page) {
    val subtitle = when (page) {
        Page.QUICK_START -> "Declare a table and run CRUD in a few minutes"
        Page.INSTALLATION -> "Add Kormium to your Gradle build"
        Page.BENCHMARKS -> "Kormium vs Exposed vs Hibernate on PostgreSQL"
        else -> ""
    }
    val compact = LocalCompact.current
    Column(Modifier.fillMaxSize().background(SCHEME.background)) {
        TopBar(title = page.label, subtitle = subtitle)
        Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
        Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().align(Alignment.TopCenter).padding(if (compact) 12.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppCard {
                    Column(Modifier.padding(if (compact) 16.dp else 24.dp)) { MarkdownView(docText(page)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Open full docs ↗") { openUrl(KORMIUM_DOCS_URL) }
                }
            }
        }
    }
}

private fun docText(page: Page): String = when (page) {
    Page.QUICK_START -> DOC_QUICK_START
    Page.INSTALLATION -> DOC_INSTALLATION
    Page.BENCHMARKS -> DOC_BENCHMARKS
    else -> ""
}

private sealed interface MdBlock
private data class MdHeading(val level: Int, val text: String) : MdBlock
private data class MdPara(val text: String) : MdBlock
private data class MdCode(val text: String) : MdBlock
private data class MdBullets(val items: List<String>) : MdBlock
private data class MdTable(val header: List<String>, val rows: List<List<String>>) : MdBlock

// Strip the inline Markdown this renderer doesn't style: links become their text, and the
// code-span backticks and bold markers are removed.
private fun inlineMd(s: String): String {
    var r = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s) { it.groupValues[1] }
    r = r.replace("**", "").replace("`", "").replace("\\*", "*")
    return r
}

private fun mdRow(line: String): List<String> = line.trim().trim('|').split("|").map { inlineMd(it.trim()) }

private fun parseMarkdown(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = md.split('\n')
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out.add(MdPara(inlineMd(para.toString().trim())))
        para.setLength(0)
    }
    while (i < lines.size) {
        val t = lines[i].trim()
        when {
            t.startsWith("```") -> {
                flushPara()
                val sb = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.append(lines[i]).append('\n'); i++ }
                i++ // closing fence
                out.add(MdCode(sb.toString().trimEnd('\n')))
            }
            t.startsWith("#") -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length
                out.add(MdHeading(level, inlineMd(t.drop(level).trim())))
                i++
            }
            t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().startsWith("|") && lines[i + 1].contains("---") -> {
                flushPara()
                val header = mdRow(t)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(mdRow(lines[i])); i++ }
                out.add(MdTable(header, rows))
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                flushPara()
                val items = ArrayList<String>()
                while (i < lines.size && (lines[i].trim().startsWith("- ") || lines[i].trim().startsWith("* "))) {
                    items.add(inlineMd(lines[i].trim().drop(2).trim())); i++
                }
                out.add(MdBullets(items))
            }
            t.isEmpty() -> { flushPara(); i++ }
            else -> { para.append(' ').append(t); i++ }
        }
    }
    flushPara()
    return out
}

@Composable
private fun MarkdownView(md: String) {
    val blocks = remember(md) { parseMarkdown(md) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { b ->
            when (b) {
                // The H1 duplicates the page title in the TopBar, so it's skipped.
                is MdHeading -> if (b.level > 1) Text(
                    b.text,
                    fontSize = if (b.level == 2) 19.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SCHEME.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdPara -> Text(b.text, fontSize = 14.sp, color = SCHEME.onSurface)
                is MdCode -> CodeBlock(b.text)
                is MdBullets -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    b.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", fontSize = 14.sp, color = Muted)
                            Text(item, fontSize = 14.sp, color = SCHEME.onSurface)
                        }
                    }
                }
                is MdTable -> MdTableView(b)
            }
        }
    }
}

@Composable
private fun MdTableView(t: MdTable) {
    val compact = LocalCompact.current
    val cols = t.header.size.coerceAtLeast(1)
    val outer = if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
    val inner = if (compact) Modifier.width((cols * 130).dp) else Modifier.fillMaxWidth()
    Box(outer) {
    Column(inner.clip(RoundedCornerShape(8.dp)).border(1.dp, CardBorder, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().background(SCHEME.surfaceVariant)) {
            t.header.forEach { c ->
                Text(c, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SCHEME.onSurface)
            }
        }
        t.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardBorder))
            Row(Modifier.fillMaxWidth()) {
                for (k in 0 until cols) {
                    Text(row.getOrElse(k) { "" }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 12.sp, color = SCHEME.onSurface)
                }
            }
        }
    }
    }
}

// ---------- doc content (excerpted from /docs in the Kormium repo) ----------

private val DOC_QUICK_START = """
# Quick Start

This guide creates a catalog, declares a table, connects to PostgreSQL and runs basic CRUD.

## 1. Define a Catalog

A `Catalog` is a type tag for one logical database. It has no runtime data; it exists so the
compiler can reject using tables with the wrong database handle.

```kotlin
import io.github.kormium.Catalog

object App : Catalog
```

## 2. Define a Table and Entity

```kotlin
object Users : Table<App, User>("users", ::User) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val age by Column.Int()
    val note by Column.Text().nullable()
}

class User : Entity() {
    var id by Users.id
    var name by Users.name
    var age by Users.age
    var note by Users.note
}
```

Columns register themselves when the delegated properties are declared. Read operations
select columns in declaration order and map rows back into entity fields.

## 3. Connect

```kotlin
val db: Database<App> = createDatabase(
    host = "localhost",
    port = 5432,
    database = "postgres",
    user = "postgres",
    password = "password",
    poolSize = 10,
)
```

Assigning the factory result to `Database<App>` pins the catalog type. The driver itself is
catalog-agnostic.

## 4. Insert and Read

```kotlin
val user = User().apply {
    id = Uuid.random()
    name = "Ada"
    age = 36
}

db.transaction {
    Users.insert(user)
}

val adults: List<User> = db.autocommit {
    Users.find {
        where { Users.age gtEq 18 }
        orderBy DESC Users.age
        limit = 50
    }
}
```

`transaction { }` wraps the block in `BEGIN` / `COMMIT` / `ROLLBACK`. `autocommit { }`
pins one connection without an explicit transaction, which is useful for simple reads.

## 5. Update and Delete

```kotlin
db.transaction {
    Users.update(User().apply { age = 37 }) {
        where { Users.id eq user.id }
    }

    Users.deleteWhere {
        where { Users.name eq "Ada" }
    }
}
```

`update` only writes properties assigned on the patch entity. An untouched property is
omitted, while a property explicitly set to `null` is written as SQL `NULL`.

## SQLite Variant

The table and query code stays the same. Swap only the factory:

```kotlin
val db: Database<App> = createSqliteDatabase()      // in-memory
val fileDb: Database<App> = createSqliteDatabase("app.db")
```
""".trimIndent()

private val DOC_INSTALLATION = """
# Installation

Kormium is published to Maven Central under the group `io.github.kormium`.

The recommended Gradle setup is to import `kormium-bom` once and then declare artifacts
without versions:

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:$KORMIUM_VERSION"))

    implementation("io.github.kormium:kormium-postgres")
    // or:
    implementation("io.github.kormium:kormium-sqlite")
    // or, JVM-only true async PostgreSQL:
    implementation("io.github.kormium:kormium-r2dbc")
}
```

## Requirements

- JDK 21 or newer for JVM builds.
- Kotlin Multiplatform project setup if you use Native, Android or iOS targets.
- PostgreSQL client libraries for the Native PostgreSQL driver.
- SQLite headers for Native SQLite on Linux if your distribution does not install them by default.

## Artifacts

| Artifact | Add when |
| --- | --- |
| kormium-core | You implement a custom backend or only need the common DSL types |
| kormium-decimal | You store exact decimals (numeric / DECIMAL columns) |
| kormium-postgres | You use PostgreSQL through JDBC on JVM or libpq on Native |
| kormium-sqlite | You use SQLite on JVM, Native or Android |
| kormium-r2dbc | You want non-blocking PostgreSQL on JVM |
| kormium-migrate | You want a raw-SQL schema migration runner |
| kormium-observe | You want reactive Flow queries that re-emit when data changes |
| kormium-ktor | You want explicit database passing in Ktor routes |
| kormium-bom | Always — pins one consistent version across all artifacts |

Backend artifacts bring `kormium-core` transitively. `kormium-observe` is pure common code and
supports the same targets as `kormium-core` (JVM, Native, Android, iOS).

## Platform Matrix

| Platform | postgres | sqlite | r2dbc |
| --- | --- | --- | --- |
| JVM | JDBC/HikariCP | sqlite-jdbc | Yes |
| Linux Native | libpq | sqlite3 | No |
| macOS Native | libpq | sqlite3 | No |
| Android | No | AndroidX SQLite | No |
| iOS | No | sqlite3 | No |
| Wasm | No | Planned | No |
""".trimIndent()

private val DOC_BENCHMARKS = """
# Benchmarks

The full comparison matrix: **Kormium JVM**, **Kormium Native** (libpq, no JVM), **Exposed**
and **Hibernate**, all against the same PostgreSQL workload.

## TL;DR

```bash
# from the repo root; the only prerequisite is a running Docker daemon
./benchmarks/run.sh
```

The script starts one tuned PostgreSQL container, runs the Kotlin/Native harness, then the
JVM JMH benchmarks, and prints a merged summary (~20 minutes):

```
Benchmark summary — ops/s, higher is better
════════════════════════════════════════════
Operation    Kormium JVM  Kormium Native  Exposed  Hibernate
------------------------------------------------------------
findById          16,253          22,988    7,688     15,535
selectWhere       16,022          24,096    7,637     15,873
```

Useful flags:

| Flag | Effect |
| --- | --- |
| --quick | fast indicative run, ~3 minutes — for checking the setup, not for quoting |
| --skip-native | JVM ORMs only (works on hosts without a native toolchain) |
| --skip-jvm | native harness only, then re-render the merged summary |

## What is measured

Six operations per ORM, all against the same table (uuid primary key, `text` + `numeric`
columns, index on `name`), 8 benchmark threads, connection pool of 8 for every ORM:

| Operation | Shape |
| --- | --- |
| findById | SELECT by primary key, autocommit |
| selectWhere | SELECT ... WHERE name = ?, index lookup returning 1 row |
| selectMany | same, returning 100 rows — measures row materialization |
| insert | single-row INSERT in a transaction |
| batchInsert | 50 rows per transaction, each ORM's idiomatic batch API |
| updateById | single-row UPDATE by primary key (random row out of 1024) |

Competitor versions: Exposed 1.0.0-beta-4 and Hibernate ORM 7.0.2.Final, both via HikariCP.

## Methodology and stability

- JMH 1.37; per benchmark: 2 forks, 5×2s warmup + 5×2s measurement, fixed 2 GiB heap.
- PostgreSQL (postgres:16-alpine) keeps its data directory on tmpfs and runs with fsync,
  synchronous_commit and full_page_writes off. The benchmarks measure ORM/driver overhead,
  not disk latency. All ORMs get the same database.
- The table is truncated and reseeded between iterations, so write benchmarks do not grow it
  and drag later iterations.

## Honesty notes

These are the project's own benchmarks. Treat every number as relative: compare columns within
one run on one machine, not against tables published elsewhere. The native column comes from a
simpler harness than JMH. Run everything on your own hardware and database before making
architecture decisions.
""".trimIndent()

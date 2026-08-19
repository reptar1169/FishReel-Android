package com.reptar.fishreel.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reptar.fishreel.model.Post
import com.reptar.fishreel.model.SpeciesCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How many days are visible in the chart's viewport without scrolling - the x-axis is
 * horizontally scrollable (see LineChartCanvas) so this is just the default/no-scroll width, not
 * a cap on how much history is plotted. Kept short so the x-axis labels stay readable instead of
 * crowding together. Mirrors iOS's FishCountsGraphView.visibleDayCount.
 */
private const val VISIBLE_DAY_COUNT = 5

private val SERIES_COLORS = listOf(
    Color(0xFF16645F), // brand teal
    Color(0xFFD9A441), // brand gold
    Color(0xFFC0392B),
    Color(0xFF2E86C1),
    Color(0xFF8E44AD),
    Color(0xFF27AE60),
    Color(0xFFE67E22),
    Color(0xFF7F8C8D),
)

/** One species' plotted points across all of history: (dayIndex, count) pairs. A species missing
 * from a given day's report is simply skipped rather than treated as a confirmed zero - the
 * line jumps straight to the next day it *was* reported, matching how iOS's Swift Charts
 * LineMark only plots the days a species actually has data for. */
private data class SeriesPoints(val species: String, val points: List<Pair<Int, Int>>)

/**
 * Two line graphs built from every FishReel Reports bot post ever posted: one for pelagic
 * species and one for coastal species - the same two groupings the daily scraper buckets
 * species into server-side (see aggregateSpeciesCounts in functions/index.js). Rendered inline
 * as the Reports tab's default content (see FeedScreen's reportsShowFeed toggle) rather than a
 * separate pushed screen, since the graph is now the "front page" and the post list is the
 * secondary view behind a toolbar button - no Scaffold/TopAppBar of its own.
 *
 * All of history is kept (not just a recent slice) since each chart's x-axis is horizontally
 * scrollable - see LineChartCanvas - defaulting to the most recent VISIBLE_DAY_COUNT days with
 * older ones a scroll away, rather than only ever showing a fixed recent window.
 *
 * Hand-rolled with a plain Canvas rather than a charting library, since only a handful of
 * simple multi-series line charts are needed here - not worth taking on a new Gradle
 * dependency for. Mirrors iOS's FishCountsGraphView (Swift Charts) closely in behavior.
 */
@Composable
fun FishCountsGraphContent(
    reportPosts: List<Post>,
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier
) {
    val sortedPosts = reportPosts
        .filter { it.createdAt != null }
        .sortedBy { it.createdAt!!.seconds }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "San Diego Dock Totals",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (sortedPosts.isEmpty()) {
            Text(
                "Species counts will start showing up here after the next daily report.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val dayLabels = sortedPosts.map { formatWeekday(it.createdAt!!.toDate()) }
            val pelagicSeries = buildSeries(sortedPosts) { it.speciesCountsPelagic }
            val coastalSeries = buildSeries(sortedPosts) { it.speciesCountsCoastal }
            // One shared horizontal ScrollState so scrolling either chart moves both together,
            // rather than each panning independently.
            val chartScrollState = rememberScrollState()
            SpeciesGraphSection(
                title = "Pelagic Species",
                dayLabels = dayLabels,
                series = pelagicSeries,
                chartScrollState = chartScrollState
            )
            SpeciesGraphSection(
                title = "Coastal Species",
                dayLabels = dayLabels,
                series = coastalSeries,
                chartScrollState = chartScrollState
            )
        }
    }
}

private fun buildSeries(posts: List<Post>, selector: (Post) -> List<SpeciesCount>): List<SeriesPoints> {
    val bySpecies = linkedMapOf<String, MutableList<Pair<Int, Int>>>()
    posts.forEachIndexed { dayIndex, post ->
        selector(post).forEach { sc ->
            bySpecies.getOrPut(sc.species) { mutableListOf() }.add(dayIndex to sc.count)
        }
    }
    return bySpecies.map { (species, points) -> SeriesPoints(species, points) }
}

private fun formatWeekday(date: Date): String =
    SimpleDateFormat("EEE", Locale.getDefault()).format(date)

@Composable
private fun SpeciesGraphSection(
    title: String,
    dayLabels: List<String>,
    series: List<SeriesPoints>,
    chartScrollState: ScrollState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (series.isEmpty()) {
            Text(
                "No ${title.lowercase()} reported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LineChartCanvas(
                dayLabels = dayLabels,
                series = series,
                scrollState = chartScrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            SeriesLegend(series = series)
        }
    }
}

@Composable
private fun SeriesLegend(series: List<SeriesPoints>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEachIndexed { index, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SERIES_COLORS[index % SERIES_COLORS.size])
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(s.species, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * A fixed, non-scrolling y-axis label gutter (left) next to a horizontally scrollable plot
 * (right) - the two are separate Canvases sharing the same vertical math (topPadding/
 * bottomPadding/chartHeight) so their gridlines/labels line up, but only the plot's Canvas is
 * wider than its viewport and lives inside the horizontalScroll container. Without this split,
 * the y-axis numbers would scroll away with the data instead of staying put.
 *
 * Each day gets a fixed width (viewport width / VISIBLE_DAY_COUNT) rather than the whole chart
 * stretching to fit however many days exist - that's what makes VISIBLE_DAY_COUNT days fill the
 * screen with no scrollbar needed, and every additional day beyond that just extends the
 * scrollable content instead of squeezing existing days closer together.
 */
@Composable
private fun LineChartCanvas(
    dayLabels: List<String>,
    series: List<SeriesPoints>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val maxCount = (series.flatMap { it.points.map { p -> p.second } }.maxOrNull() ?: 0).coerceAtLeast(1)
    val dayCount = dayLabels.size.coerceAtLeast(1)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    val axisGutterWidth = 30.dp

    BoxWithConstraints(modifier = modifier) {
        val plotViewportWidth = (maxWidth - axisGutterWidth).coerceAtLeast(1.dp)
        val dayWidth = plotViewportWidth / VISIBLE_DAY_COUNT
        val contentWidth = dayWidth * maxOf(dayCount, VISIBLE_DAY_COUNT)

        // Default to showing the most recent days rather than the oldest. scrollTo clamps to
        // whatever the real max turns out to be once layout knows it, so a huge target value is
        // a safe "scroll all the way to the end" without racing the measurement pass. Keyed on
        // Unit so it only runs once per composable instance, not every time new data arrives -
        // otherwise it would keep yanking someone back to "today" while they're scrolled back
        // through history.
        LaunchedEffect(Unit) {
            scrollState.scrollTo(Int.MAX_VALUE)
        }

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Canvas(modifier = Modifier.width(axisGutterWidth).fillMaxHeight()) {
                val topPadding = 8.dp.toPx()
                val bottomPadding = 20.dp.toPx()
                val chartHeight = (size.height - topPadding - bottomPadding).coerceAtLeast(1f)

                fun yFor(count: Int): Float =
                    topPadding + chartHeight - (chartHeight * count / maxCount.toFloat())

                val gridSteps = 4
                for (step in 0..gridSteps) {
                    val value = maxCount * step / gridSteps
                    drawText(
                        textMeasurer = textMeasurer,
                        text = value.toString(),
                        topLeft = Offset(0f, (yFor(value) - 6.dp.toPx()).coerceAtLeast(0f)),
                        style = labelStyle
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                ) {
                    val topPadding = 8.dp.toPx()
                    val bottomPadding = 20.dp.toPx()
                    val rightPadding = 8.dp.toPx()
                    val chartHeight = (size.height - topPadding - bottomPadding).coerceAtLeast(1f)
                    val dayWidthPx = dayWidth.toPx()

                    fun xFor(dayIndex: Int): Float = dayIndex * dayWidthPx + dayWidthPx / 2f

                    fun yFor(count: Int): Float =
                        topPadding + chartHeight - (chartHeight * count / maxCount.toFloat())

                    // Y-axis gridlines, spanning the full scrollable width so they stay visible
                    // as guides no matter where the plot is scrolled to.
                    val gridSteps = 4
                    for (step in 0..gridSteps) {
                        val value = maxCount * step / gridSteps
                        val y = yFor(value)
                        drawLine(
                            color = axisColor.copy(alpha = 0.15f),
                            start = Offset(0f, y),
                            end = Offset(size.width - rightPadding, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // X-axis day-of-week labels, one per fixed-width day slot.
                    dayLabels.forEachIndexed { index, label ->
                        val measured = textMeasurer.measure(label, labelStyle)
                        val x = xFor(index) - measured.size.width / 2f
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            topLeft = Offset(x, size.height - bottomPadding + 4.dp.toPx()),
                            style = labelStyle
                        )
                    }

                    // One polyline + point markers per species.
                    series.forEachIndexed { seriesIndex, s ->
                        val color = SERIES_COLORS[seriesIndex % SERIES_COLORS.size]
                        val sortedPoints = s.points.sortedBy { it.first }
                        if (sortedPoints.size == 1) {
                            val (day, count) = sortedPoints[0]
                            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(xFor(day), yFor(count)))
                        } else if (sortedPoints.isNotEmpty()) {
                            val path = Path()
                            sortedPoints.forEachIndexed { i, (day, count) ->
                                val point = Offset(xFor(day), yFor(count))
                                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                            }
                            drawPath(path = path, color = color, style = Stroke(width = 2.5.dp.toPx()))
                            sortedPoints.forEach { (day, count) ->
                                drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(xFor(day), yFor(count)))
                            }
                        }
                    }
                }
            }
        }
    }
}

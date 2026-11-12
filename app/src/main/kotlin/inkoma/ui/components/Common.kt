package inkoma.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import inkoma.ui.theme.InkomaIcons
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Mihon's MaterialTheme.padding values. */
object Padding {
    val extraLarge = 32.dp
    val large = 24.dp
    val medium = 16.dp
    val small = 8.dp
    val extraSmall = 4.dp
}

object CommonMangaItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp
    const val BrowseFavoriteCoverAlpha = 0.34f
}

const val BOOK_RATIO = 2f / 3f

@Composable
fun MangaCover(
    url: String?,
    sourceId: Long,
    modifier: Modifier = Modifier,
    coverLastModified: Long = 0,
    persistent: Boolean = false,
    alpha: Float = 1f,
) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        RemoteImage(
            url = url,
            sourceId = sourceId,
            modifier = Modifier.fillMaxSize().then(if (alpha < 1f) Modifier.background(Color.Transparent) else Modifier),
            contentScale = ContentScale.Crop,
            persistent = persistent,
            cacheKeySuffix = if (coverLastModified > 0) "@$coverLastModified" else "",
        )
        if (alpha < 1f) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 1f - alpha)))
    }
}

@Composable
fun BadgeGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier.clip(MaterialTheme.shapes.extraSmall), content = content)
}

@Composable
fun Badge(
    text: String,
    color: Color = MaterialTheme.colorScheme.secondary,
    textColor: Color = MaterialTheme.colorScheme.onSecondary,
) {
    Text(
        text = text,
        modifier = Modifier.background(color).padding(horizontal = 3.dp, vertical = 1.dp),
        color = textColor,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
    )
}

@Composable
fun RowScope.UnreadBadge(count: Long) {
    if (count > 0) Badge(text = count.toString())
}

@Composable
fun RowScope.DownloadsBadge(count: Int) {
    if (count > 0) Badge(text = count.toString(), color = MaterialTheme.colorScheme.tertiary, textColor = MaterialTheme.colorScheme.onTertiary)
}

@Composable
fun RowScope.InLibraryBadge(enabled: Boolean) {
    if (enabled) Badge(text = "In library", color = MaterialTheme.colorScheme.secondary, textColor = MaterialTheme.colorScheme.onSecondary)
}

@Composable
fun RowScope.LanguageBadge(isLocal: Boolean, lang: String?) {
    if (isLocal) {
        Badge(text = "Local", color = MaterialTheme.colorScheme.tertiary, textColor = MaterialTheme.colorScheme.onTertiary)
    } else if (!lang.isNullOrEmpty()) {
        Badge(text = lang.uppercase(), color = MaterialTheme.colorScheme.tertiary, textColor = MaterialTheme.colorScheme.onTertiary)
    }
}

private fun Modifier.selectedOutline(isSelected: Boolean, color: Color) = drawBehind { if (isSelected) drawRect(color = color) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItemSelectable(
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .inkomaClickable(onClick = onClick, onLongClick = onLongClick)
            .selectedOutline(isSelected = isSelected, color = MaterialTheme.colorScheme.secondary)
            .padding(4.dp),
    ) {
        val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondary else LocalContentColor.current
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
private fun GridItemTitle(title: String, style: TextStyle, minLines: Int, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

@Composable
private fun MangaGridCover(
    modifier: Modifier = Modifier,
    cover: @Composable BoxScope.() -> Unit = {},
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().aspectRatio(BOOK_RATIO)) {
        cover()
        content?.invoke(this)
        if (badgesStart != null) BadgeGroup(Modifier.padding(4.dp).align(Alignment.TopStart), badgesStart)
        if (badgesEnd != null) BadgeGroup(Modifier.padding(4.dp).align(Alignment.TopEnd), badgesEnd)
    }
}

@Composable
private fun ContinueReadingButton(size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.size(size),
        ) {
            Icon(InkomaIcons.PlayArrow, contentDescription = "Continue reading", modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun BoxScope.CoverTextOverlay(title: String, onClickContinueReading: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xAA000000)))
            .fillMaxHeight(0.33f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
    )
    Row(modifier = Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
        GridItemTitle(
            modifier = Modifier.weight(1f).padding(8.dp),
            title = title,
            style = MaterialTheme.typography.titleSmall.copy(color = Color.White, shadow = Shadow(color = Color.Black, blurRadius = 4f)),
            minLines = 1,
        )
        if (onClickContinueReading != null) {
            ContinueReadingButton(28.dp, 16.dp, onClickContinueReading, Modifier.padding(end = 6.dp, bottom = 6.dp))
        }
    }
}

/** Compact grid (title over cover) and cover-only grid (title = null). */
@Composable
fun MangaCompactGridItem(
    coverUrl: String?,
    sourceId: Long,
    title: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    coverLastModified: Long = 0,
    persistent: Boolean = false,
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    onClickContinueReading: (() -> Unit)? = null,
) {
    GridItemSelectable(isSelected = isSelected, onClick = onClick, onLongClick = onLongClick) {
        MangaGridCover(
            cover = { MangaCover(coverUrl, sourceId, Modifier.fillMaxWidth().aspectRatio(BOOK_RATIO), coverLastModified, persistent, coverAlpha) },
            badgesStart = badgesStart,
            badgesEnd = badgesEnd,
            content = {
                if (title != null) {
                    CoverTextOverlay(title, onClickContinueReading)
                } else if (onClickContinueReading != null) {
                    ContinueReadingButton(32.dp, 20.dp, onClickContinueReading, Modifier.padding(6.dp).align(Alignment.BottomEnd))
                }
            },
        )
    }
}

@Composable
fun MangaComfortableGridItem(
    coverUrl: String?,
    sourceId: Long,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    coverLastModified: Long = 0,
    persistent: Boolean = false,
    badgesStart: (@Composable RowScope.() -> Unit)? = null,
    badgesEnd: (@Composable RowScope.() -> Unit)? = null,
    onClickContinueReading: (() -> Unit)? = null,
) {
    GridItemSelectable(isSelected = isSelected, onClick = onClick, onLongClick = onLongClick) {
        Column {
            MangaGridCover(
                cover = { MangaCover(coverUrl, sourceId, Modifier.fillMaxWidth().aspectRatio(BOOK_RATIO), coverLastModified, persistent, coverAlpha) },
                badgesStart = badgesStart,
                badgesEnd = badgesEnd,
                content = {
                    if (onClickContinueReading != null) {
                        ContinueReadingButton(32.dp, 20.dp, onClickContinueReading, Modifier.padding(6.dp).align(Alignment.BottomEnd))
                    }
                },
            )
            GridItemTitle(modifier = Modifier.padding(4.dp), title = title, style = MaterialTheme.typography.titleSmall, minLines = 2)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaListItem(
    coverUrl: String?,
    sourceId: Long,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean = false,
    coverAlpha: Float = 1f,
    coverLastModified: Long = 0,
    persistent: Boolean = false,
    badge: @Composable RowScope.() -> Unit = {},
    onClickContinueReading: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .selectedBackground(isSelected)
            .height(56.dp)
            .inkomaClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(coverUrl, sourceId, Modifier.fillMaxHeight().aspectRatio(1f), coverLastModified, persistent, coverAlpha)
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp).weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        BadgeGroup(content = badge)
        if (onClickContinueReading != null) {
            ContinueReadingButton(28.dp, 16.dp, onClickContinueReading, Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun Modifier.selectedBackground(isSelected: Boolean): Modifier =
    if (isSelected) {
        val color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
        this.drawBehind { drawRect(color) }
    } else {
        this
    }

@Composable
fun EmptyScreen(message: String, modifier: Modifier = Modifier, actions: (@Composable () -> Unit)? = null) {
    val face = remember { listOf("(･o･;)", "Σ(ಠ_ಠ)", "ಥ_ಥ", "(˘･_･˘)", "(；￣Д￣)", "(･Д･。").random() }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = face, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = message,
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actions != null) Row(Modifier.padding(top = 24.dp)) { actions() }
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

data class AppBarAction(val title: String, val icon: ImageVector? = null, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String?,
    subtitle: String? = null,
    navigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    actionModeCounter: Int = 0,
    onCancelActionMode: () -> Unit = {},
    actionModeActions: @Composable RowScope.() -> Unit = {},
) {
    val isActionMode = actionModeCounter > 0
    TopAppBar(
        title = {
            if (isActionMode) {
                Text(actionModeCounter.toString(), maxLines = 1)
            } else if (title != null) {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        navigationIcon = {
            if (isActionMode) {
                IconButton(onClick = onCancelActionMode) { Icon(InkomaIcons.Close, "Cancel") }
            } else if (navigateUp != null) {
                IconButton(onClick = navigateUp) { Icon(InkomaIcons.ArrowBack, "Back") }
            }
        },
        actions = { if (isActionMode) actionModeActions() else actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (isActionMode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        ),
    )
}

/** Mihon's SearchToolbar: an app bar that turns into a text field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchToolbar(
    title: String,
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit = {},
    placeholder: String = "Search…",
    navigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (searchQuery == null) {
        AppBar(
            title = title,
            navigateUp = navigateUp,
            actions = {
                IconButton(onClick = { onChangeSearchQuery("") }) { Icon(InkomaIcons.Search, "Search") }
                actions()
            },
        )
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        TopAppBar(
            title = {
                TextField(
                    value = searchQuery,
                    onValueChange = { onChangeSearchQuery(it) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onEnter { onSearch(searchQuery) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = { onChangeSearchQuery(null) }) { Icon(InkomaIcons.ArrowBack, "Close search") }
            },
            actions = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onChangeSearchQuery("") }) { Icon(InkomaIcons.Close, "Clear") }
                }
                actions()
            },
        )
    }
}

fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
        action()
        true
    } else {
        false
    }
}

@Composable
fun ListGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = Padding.medium, vertical = Padding.small),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun DotSeparatorText() {
    Text(text = " • ")
}

@Composable
fun BadgeCount(count: Int) {
    if (count <= 0) return
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = MaterialTheme.colorScheme.onError, fontSize = 10.sp)
    }
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Padding.medium, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun HSpacer(width: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(width))

@Composable
fun VSpacer(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

// ---- Dates ------------------------------------------------------------------------------------

fun Long.toDateKey(): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = this@toDateKey
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** Mihon's relative date text ("Today", "Yesterday", "n days ago", then absolute). */
fun relativeDateText(epochMillis: Long, relative: Boolean = true, format: String = ""): String {
    if (epochMillis <= 0) return ""
    val df: DateFormat = if (format.isBlank()) DateFormat.getDateInstance(DateFormat.SHORT) else SimpleDateFormat(format, Locale.getDefault())
    if (!relative) return df.format(Date(epochMillis))
    val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis().toDateKey() - epochMillis.toDateKey())
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days in 2..6 -> "$days days ago"
        else -> df.format(Date(epochMillis))
    }
}

fun timeText(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

fun formatChapterNumber(n: Double): String =
    if (n % 1.0 == 0.0) n.toLong().toString() else java.math.BigDecimal(n).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

package org.bayton.gmc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bayton.gmc.ui.theme.LocalBayton

/**
 * The signature body background: the canvas with a fixed, viewport-anchored ambient orange glow as
 * a radial from the top-right. Paint this once at the app root and keep scaffolds and screens
 * transparent, so the whole background is a single layer that changes together on a theme change
 * and the glow shows through everywhere.
 */
@Composable
fun Modifier.baytonBackground(): Modifier {
    val c = LocalBayton.current
    return this.drawWithCache {
        val brush = Brush.radialGradient(
            colors = listOf(c.glow, Color.Transparent),
            center = Offset(size.width * 0.84f, -size.height * 0.12f),
            radius = maxOf(size.width, size.height) * 1.15f,
        )
        onDrawBehind {
            drawRect(c.canvas)
            drawRect(brush)
        }
    }
}

/** Screen shell with a back top bar in the bayton language. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmcScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = LocalBayton.current
    Scaffold(
        containerColor = Color.Transparent,   // the root baytonBackground() shows through
        // ...but a transparent container makes Material derive an Unspecified contentColor (bare Text
        // then falls back to black). Provide the DS ink so unstyled Material content is legible in dark
        // mode without every screen having to colour each Text itself.
        contentColor = c.ink,
        topBar = {
            TopAppBar(
                title = { Text(title, color = c.ink, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = c.ink)
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = c.ink),
            )
        },
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalBayton.current
    Text(text.uppercase(), color = c.inkFaint, style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, modifier = modifier)
}

@Composable
fun BaytonCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBayton.current
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
        .border(1.dp, c.line, RoundedCornerShape(12.dp))
    Column(
        (if (onClick != null) base.clickable(interactionSource = null, indication = null, onClick = onClick) else base).padding(14.dp),
        content = content,
    )
}

@Composable
fun CountBadge(n: Int) {
    val c = LocalBayton.current
    if (n <= 0) return
    Box(Modifier.defaultMinSize(22.dp, 22.dp).clip(RoundedCornerShape(999.dp)).background(c.accent).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center) {
        Text("$n", color = Color.White, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, letterSpacing = 0.sp)
    }
}

@Composable
fun Pill(text: String, tone: PillTone = PillTone.Accent) {
    val c = LocalBayton.current
    val (fg, bg, line) = when (tone) {
        PillTone.Accent -> Triple(c.accent2, c.accentSoft, c.accentLine)
        PillTone.Muted -> Triple(c.inkDim, c.surface2, c.line2)
        PillTone.Good -> Triple(c.green, c.accentSoft.copy(alpha = 0f), c.line2)
    }
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).border(1.dp, line, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text.uppercase(), color = fg, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}
enum class PillTone { Accent, Muted, Good }

@Composable
fun Avatar(initials: String, size: Int = 36) {
    val c = LocalBayton.current
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.line2, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials.take(2).uppercase(), color = c.inkDim, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
    }
}

@Composable
fun StateBox(loading: Boolean, error: String?, empty: Boolean, emptyText: String, pad: PaddingValues, retry: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = LocalBayton.current
    when {
        loading -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        error != null -> Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Something went wrong", color = c.ink, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(error, color = c.inkDim, textAlign = TextAlign.Center, fontSize = 13.sp)
            if (retry != null) { Spacer(Modifier.height(16.dp)); TextButton(onClick = retry) { Text("Try again", color = c.accent) } }
        }
        empty -> Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Inbox, null, tint = c.inkFaint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text(emptyText, color = c.inkFaint, textAlign = TextAlign.Center, fontSize = 14.sp)
        }
        else -> content()
    }
}

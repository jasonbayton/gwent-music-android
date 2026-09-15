package org.bayton.gmc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bayton.gmc.data.cache.Freshness
import org.bayton.gmc.ui.theme.LocalBayton

/**
 * A quiet one-line freshness indicator (FR-D02). It renders ONLY when the shown data may be behind
 * (stale, offline, or the service is holding) - nothing at all when data is fresh, syncing or on a
 * reauth (handled by the screen's own prompt), so the normal case spends no vertical space. When
 * `onRetry` is supplied it offers a Retry - the recovery action a cached screen otherwise lacks.
 */
@Composable
fun FreshnessPill(freshness: Freshness, syncedAt: Long?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = LocalBayton.current
    val age = syncedAt?.let { relativeAge(it) }
    val text = when (freshness) {
        Freshness.STALE -> age?.let { "Saved $it - couldn't refresh" } ?: "Couldn't refresh"
        Freshness.OFFLINE -> age?.let { "Offline - showing saved copy ($it)" } ?: "Offline"
        Freshness.SERVICE_HOLD -> age?.let { "Gwent Music is having trouble - showing saved copy ($it)" }
            ?: "Gwent Music is having trouble"
        Freshness.CURRENT, Freshness.SYNCING, Freshness.REAUTH_REQUIRED -> return  // nothing worth a line
    }
    // fillMaxWidth + a weighted, ellipsised status text guarantees Retry keeps its space even with a
    // long message or enlarged font, so the recovery action can never be squeezed off-screen.
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.width(7.dp))
        Text(text, color = c.accent2, fontSize = 11.5f.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onRetry != null) {
            Spacer(Modifier.width(10.dp))
            Text("Retry", color = c.accent, fontSize = 11.5f.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRetry).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

private fun relativeAge(syncedAt: Long): String {
    val mins = ((System.currentTimeMillis() - syncedAt).coerceAtLeast(0)) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

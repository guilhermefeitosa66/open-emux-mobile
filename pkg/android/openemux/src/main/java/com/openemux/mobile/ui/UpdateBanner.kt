package com.openemux.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openemux.mobile.data.Release
import com.openemux.mobile.data.UpdateState
import com.retroarch.BuildConfig
import com.retroarch.R

/** The newer release, while the check found one and "Not now" was not pressed; null otherwise. */
@Composable
fun pendingUpdate(): Release? {
    val updates = LocalActions.current.app.updates
    val state by updates.state.collectAsState()
    val dismissed by updates.dismissed.collectAsState()
    return (state as? UpdateState.Available)?.release?.takeIf { !dismissed }
}

/** The non-modal "new version" banner on the shelf; takes no space when there is nothing to show. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val updates = LocalActions.current.app.updates
    val uri = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val release = pendingUpdate()
    var last by remember { mutableStateOf(release) }
    if (release != null) last = release

    AnimatedVisibility(release != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val shown = last ?: return@AnimatedVisibility
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Oe.AccentTint)
                .border(1.dp, Oe.AccentSoft, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Download, Oe.AccentText, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_available_title, shown.version), style = Type.Section)
                    Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME), style = Type.Small, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.update_not_now), style = Type.Button.copy(color = Oe.AccentText),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .gamepadFocus(corner = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            // Hand focus to the shelf below before the button goes away.
                            if (InputMode.gamepad) focus.moveFocus(FocusDirection.Down)
                            updates.dismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
                Text(
                    stringResource(R.string.update_download), style = Type.Button.copy(color = Color.White),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .gamepadFocus(corner = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Oe.Accent)
                        .clickable(role = Role.Button) { runCatching { uri.openUri(shown.url) } }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }
    }
}

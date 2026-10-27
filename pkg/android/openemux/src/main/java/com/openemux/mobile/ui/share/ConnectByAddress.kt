package com.openemux.mobile.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openemux.mobile.share.ConnectOutcome
import com.openemux.mobile.share.ShareFormat
import com.openemux.mobile.share.qrBitmap
import com.openemux.mobile.ui.BackLink
import com.openemux.mobile.ui.Card
import com.openemux.mobile.ui.FocusMargin
import com.openemux.mobile.ui.GamepadButtons
import com.openemux.mobile.ui.Icons
import com.openemux.mobile.ui.LocalActions
import com.openemux.mobile.ui.MarginFocusScroll
import com.openemux.mobile.ui.Oe
import com.openemux.mobile.ui.OeTextField
import com.openemux.mobile.ui.PrimaryButton
import com.openemux.mobile.ui.SecondaryButton
import com.openemux.mobile.ui.SectionHeader
import com.openemux.mobile.ui.Type
import com.openemux.mobile.ui.hintBarInset
import com.openemux.mobile.ui.restorableFocus
import com.retroarch.R
import kotlinx.coroutines.launch

/** Route `share/address` (spec 03): this device's QR and address, and a field for the other one's. [initial] comes from a link. */
@Composable
fun ConnectByAddressScreen(initial: String) {
    val a = LocalActions.current
    val share = a.app.shareCoordinator
    val context = LocalContext.current
    DisposableEffect(Unit) {
        share.openScreen(send = false)
        onDispose { share.closeScreen() }
    }
    GamepadButtons(hints = listOf("A" to stringResource(R.string.settings_hint_choose), "B" to stringResource(R.string.settings_hint_back)))
    val own by share.ownAddress.collectAsState()
    var text by rememberSaveable { mutableStateOf(initial) }
    var checking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val focus = rememberSaveable { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.share_address_copied)
    fun connect() {
        if (checking || text.isBlank()) return
        checking = true
        outcome = null
        scope.launch {
            val result = share.connect(text)
            checking = false
            outcome = result
            if (result.error == null) a.nav.popBackStack()
        }
    }

    MarginFocusScroll(trailing = FocusMargin + hintBarInset) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
                BackLink(stringResource(R.string.share_title)) { a.nav.popBackStack() }
                Text(stringResource(R.string.share_address_title), style = Type.Title, modifier = Modifier.padding(top = 4.dp))
            }
            SectionHeader(stringResource(R.string.share_this_device))
            Card(Modifier.padding(horizontal = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val address = own
                    if (address == null) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Oe.AccentText, strokeWidth = 2.dp)
                    } else {
                        val uri = address.format()
                        val px = with(LocalDensity.current) { 156.dp.roundToPx() }
                        val qr = remember(uri, px) { qrBitmap(uri, px) }
                        val qrLabel = stringResource(R.string.share_address_qr)
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                            Image(qr, qrLabel, Modifier.size(156.dp), filterQuality = FilterQuality.None)
                        }
                        Text("${address.host}:${address.port}", style = Type.Body.copy(fontFamily = FontFamily.Monospace, fontSize = 20.sp))
                        address.fp?.let { fp ->
                            ShareFormat.fingerprintLines(fp).forEach {
                                Text(it, style = Type.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Oe.Muted))
                            }
                        }
                        SecondaryButton(
                            stringResource(R.string.share_address_copy),
                            {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenEmux", uri))
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            },
                            Modifier.fillMaxWidth().padding(top = 4.dp), icon = Icons.Copy,
                        )
                    }
                    Text(stringResource(R.string.share_address_scan_hint), style = Type.Small)
                }
            }
            SectionHeader(stringResource(R.string.share_address_other))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val error = outcome?.error
                OeTextField(
                    stringResource(R.string.share_address_label), text, { text = it; outcome = null },
                    Modifier.fillMaxWidth().restorableFocus("address", focus, initial = true),
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                )
                if (error != null) {
                    Text(
                        messageText(error, text.trim(), outcome?.peerVersion),
                        style = Type.Small.copy(color = Oe.Danger),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                Text(stringResource(R.string.share_address_camera_hint), style = Type.Small)
                Box(contentAlignment = Alignment.Center) {
                    PrimaryButton(if (checking) "" else stringResource(R.string.share_address_connect), ::connect, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
                    if (checking) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(28.dp + hintBarInset))
        }
    }
}

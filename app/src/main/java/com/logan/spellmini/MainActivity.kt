package com.logan.spellmini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.logan.spellmini.notify.KeepAliveService
import com.logan.spellmini.ui.ChatScreen
import com.logan.spellmini.ui.FeedScreen
import com.logan.spellmini.ui.HubScreen
import com.logan.spellmini.ui.Ink
import com.logan.spellmini.ui.SpellTheme

enum class Tab { CHAT, FEED }

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Bumped when a system notification asks us to land on the chat tab. */
    private val openChatSignal = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SpellTheme { Root(openChatSignal.value) } }
    }

    override fun onResume() {
        super.onResume()
        Graph.appInForeground.value = true
    }

    override fun onPause() {
        Graph.appInForeground.value = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) openChatSignal.value += 1
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}

@Composable
private fun Root(openChatSignal: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var hubOpen by rememberSaveable { mutableStateOf(false) }
    // A feed card's "讨论" hands its content to the chat composer.
    var chatDraftContext by remember { mutableStateOf<String?>(null) }
    val connected by Graph.listenerConnected.collectAsState()

    var lastSignal by remember { mutableStateOf(openChatSignal) }
    if (openChatSignal != lastSignal) {
        lastSignal = openChatSignal
        tab = Tab.CHAT
        hubOpen = false
    }

    BackHandler(enabled = hubOpen) { hubOpen = false }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).statusBarsPadding()) {
        if (hubOpen) {
            HubScreen(onBack = { hubOpen = false })
        } else {
            TopBar(tab = tab, onTab = { tab = it }, needsAttention = !connected, onBall = { hubOpen = true })
            HorizontalDivider(color = Ink.Line)
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.CHAT -> ChatScreen(
                        pendingContext = chatDraftContext,
                        onContextConsumed = { chatDraftContext = null },
                    )
                    Tab.FEED -> FeedScreen(onDiscuss = { context ->
                        chatDraftContext = context
                        tab = Tab.CHAT
                    })
                }
            }
        }
    }
}

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, needsAttention: Boolean, onBall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabLabel("Chat", Icons.Outlined.ChatBubbleOutline, tab == Tab.CHAT) { onTab(Tab.CHAT) }
        Spacer(Modifier.width(20.dp))
        TabLabel("Feed", Icons.Outlined.Newspaper, tab == Tab.FEED) { onTab(Tab.FEED) }
        Spacer(Modifier.weight(1f))
        // The "ball": entry to the trace, profile and settings. A red dot means the listener is not connected.
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Ink.Faint).clickable(onClick = onBall),
            )
            if (needsAttention) Box(Modifier.size(10.dp).clip(CircleShape).background(Ink.Red))
        }
    }
}

@Composable
private fun TabLabel(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Ink.Black else Ink.Muted
    Row(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

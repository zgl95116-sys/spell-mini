package com.logan.spellmini.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logan.spellmini.Graph
import com.logan.spellmini.data.FeedCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A finished piece of work, full screen. JavaScript stays off: the text came from a model that read the open web. */
@Composable
fun DocScreen(cardId: Long, onBack: () -> Unit, onRevise: (String) -> Unit) {
    val context = LocalContext.current
    var card by remember(cardId) { mutableStateOf<FeedCard?>(null) }
    LaunchedEffect(cardId) { card = withContext(Dispatchers.IO) { Graph.db.feed().get(cardId) } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink.Black) }
            Text(card?.title.orEmpty(), color = Ink.Black, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            card?.let { doc ->
                Pill("再改一版", Ink.Black) { onRevise("成品 #${doc.id}《${doc.title}》\n" + doc.body.take(1_500)) }
                Spacer(Modifier.padding(start = 8.dp))
                Pill("分享", Ink.Blue) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, doc.title).putExtra(Intent.EXTRA_TEXT, doc.body)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }
                Spacer(Modifier.padding(end = 8.dp))
            }
        }
        HorizontalDivider(color = Ink.Line)
        card?.let { doc ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            // Links leave the app: the viewer itself never loads a remote page.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }
                        }
                    }
                },
                update = { view -> view.loadDataWithBaseURL(null, Markdown.page(doc.title, doc.body), "text/html", "utf-8", null) },
            )
        }
    }
}

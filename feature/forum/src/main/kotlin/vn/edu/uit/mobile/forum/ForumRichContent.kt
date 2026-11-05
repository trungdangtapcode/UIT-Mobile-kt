package vn.edu.uit.mobile.forum

import androidx.compose.runtime.Composable
import vn.edu.uit.mobile.designsystem.SemanticArticleBlock
import vn.edu.uit.mobile.domain.*

/** Forum-specific plugin fallback over the shared native article renderer. */
@Composable internal fun ForumBlockView(block: ForumBlock, onLink: (String) -> Unit, imageLoader: suspend (String) -> ByteArray, source: String, onBrowser: (String) -> Unit, pollContent: (@Composable (ForumPoll) -> Unit)? = null) {
    SemanticArticleBlock(block, onLink, imageLoader, source, onBrowser, autoLoadImages = true) { poll ->
        if (pollContent != null) pollContent(poll) else ForumPollView(poll, false, 0, {}, {}, onLink, imageLoader, source)
    }
}

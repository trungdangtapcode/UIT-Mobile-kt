package vn.edu.uit.mobile.forum.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import vn.edu.uit.mobile.domain.ForumNetworkIssue

/** Safe categories only: no exception messages, response bodies, credentials or URLs. */
internal fun forumNetworkIssue(error: Throwable): ForumNetworkIssue {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SSLException } -> ForumNetworkIssue.TLS
        causes.any { it is UnknownHostException } -> ForumNetworkIssue.DNS
        causes.any { it is SocketTimeoutException || it.javaClass.simpleName.endsWith("TimeoutException") } -> ForumNetworkIssue.TIMEOUT
        causes.any { it is ConnectException } -> ForumNetworkIssue.CONNECT
        causes.any { it is IOException } -> ForumNetworkIssue.IO
        else -> ForumNetworkIssue.UNKNOWN
    }
}

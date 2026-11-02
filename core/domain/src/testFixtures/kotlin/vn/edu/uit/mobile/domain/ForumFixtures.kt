package vn.edu.uit.mobile.domain

/** Authored public-reader examples. These are not copied university posts or identities. */
object ForumFixtures {
    const val AT = 1793671200L
    const val TITLE = "Getting started with the campus Linux lab"
    val category = ForumCategory(8, "Study corner")
    val child = ForumCategory(29, "Operating systems", 8)
    val topic = ForumTopic(901, TITLE, 29, 125, "2026-11-03T02:00:00Z", true, false, listOf("linux", "laboratory"))
    val second = ForumTopic(902, "Library opening hours during exam week", 8, 7, "2026-11-02T10:00:00Z")
    private const val HTML = "<h3>Prepare your workspace</h3><p>Use a separate folder for each lab. Check the course instructions before running a command.</p><aside class='quote'><p>Keep your original files before experimenting.</p></aside><pre><code>mkdir lab-01\ncd lab-01\nprintf 'ready\\n'</code></pre><ul><li>Read the task</li><li>Test with a small input</li></ul><table><tr><th>Command</th><th>Purpose</th></tr><tr><td>pwd</td><td>Show working directory</td></tr></table><details><summary>Why keep a backup?</summary><p>It makes the experiment easy to repeat.</p></details><p><a href='https://example.org/guide'>Open the lab guide</a></p><img src='/uploads/default/original/1X/synthetic.png' alt='Authored lab diagram'>"
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun post(index: Int): String = """{"id":${1000 + index},"topic_id":901,"post_number":${index * 2 - 1},"username":"lab_helper","created_at":"2026-11-03T02:00:00Z","cooked":"${escape(if (index == 1) HTML else "<p>Synthetic reply $index: compare the output with the task and record what changed.</p>")}"}"""
    private fun topicJson(number: Int): String {
        val center = ((number + 1) / 2).coerceIn(1, 125)
        val start = (center - 5).coerceIn(1, 106)
        return """{"id":901,"title":"$TITLE","category_id":29,"archetype":"regular","posts_count":125,"pinned":true,"tags":["linux","laboratory"],"post_stream":{"stream":[${(1..125).joinToString(",") { (1000 + it).toString() }}],"posts":[${(start until start + 20).joinToString(",", transform = ::post)}]}}"""
    }
    const val CATEGORIES = """{"categories":[{"id":8,"name":"Study corner"},{"id":29,"parent_category_id":8,"name":"Operating systems"},{"id":99,"name":"Private area","read_restricted":true}]}"""
    const val LATEST = """{"topic_list":{"more_topics_url":"/latest?page=1","topics":[{"id":901,"title":"Getting started with the campus Linux lab","category_id":29,"posts_count":125,"last_posted_at":"2026-11-03T02:00:00Z","pinned":true},{"id":902,"title":"Library opening hours during exam week","category_id":8,"posts_count":7,"last_posted_at":"2026-11-02T10:00:00Z"}]}}"""
    const val SEARCH = """{"topics":[{"id":901,"title":"Getting started with the campus Linux lab","category_id":29,"posts_count":125}],"posts":[{"id":1050,"topic_id":901,"post_number":99,"blurb":"Try a <span class='search-highlight'>small input</span> before the full dataset."}],"grouped_search_result":{"more_full_page_results":false}}"""
    fun response(request: ForumRequest): String = when (request) {
        is ForumRequest.Revision -> error("Revision fixture must be supplied explicitly")
        ForumRequest.Categories -> CATEGORIES
        is ForumRequest.Category -> """{"category":{"id":${request.id},"name":"Public category"}}"""
        is ForumRequest.Latest -> if (request.page == 0) LATEST else """{"topic_list":{"topics":[]}}"""
        is ForumRequest.Search -> SEARCH
        is ForumRequest.Topic -> topicJson(request.postNumber)
        is ForumRequest.Posts -> """{"id":901,"post_stream":{"posts":[${request.ids.map { (it - 1000).toInt() }.filter { it in 1..125 }.reversed().joinToString(",", transform = ::post)}]}}"""
    }
}

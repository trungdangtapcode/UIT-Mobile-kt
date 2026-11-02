package vn.edu.uit.mobile.domain

/** Authored synthetic code and expectations; not student code or a human-reviewed benchmark. */
object TutorFixtures {
    val code = "def first(values):\n    return values[0]\n"
    val request = TutorRequest(TutorSnippet(code, "Python", 10), "What happens for an empty list?", environment = "Python 3.11; values is a list")
    val reference = TutorCodeReference(11, 11, "return values[0]")
    val context = StudySource("lesson:stack", "Synthetic lesson", "A **stack** removes the most recently added item first.")
    fun result(request: TutorRequest = this.request): TutorResult {
        val reference = this.reference.copy(startLine = request.snippet.firstLine + 1, endLine = request.snippet.firstLine + 1)
        return TutorResult(
            "synthetic-code-tutor", 1793386800, request.version, "ready", "Inspect the input contract before indexing a list.",
            listOf(TutorPoint("code", "The function indexes element zero without checking for an empty list.", listOf(reference)), TutorPoint("suggestion", "For a standard Python list, indexing is constant time. Other containers may differ.")),
            listOf(
                TutorPoint("suggestion", "Which index is available when the list is empty?", listOf(reference)),
                TutorPoint("suggestion", "Consider checking whether the list contains an item before indexing.", listOf(reference)),
                TutorPoint("suggestion", "Define an empty-input outcome, then return the first element for nonempty lists.", listOf(reference))
            ),
            listOf(TutorTestCase("Empty list", "first([])", "IndexError is predicted for a Python list; not run.", "There is no element at index zero.", listOf(reference)), TutorTestCase("Single item", "first([7])", "7 is predicted; not run.", "A one-element list has index zero.", listOf(reference))),
            "def first(values):\n    if not values:\n        return None\n    return values[0]\n", "Returning None is one possible empty-input contract. Review the caller's expectations before using this edit."
        )
    }
}

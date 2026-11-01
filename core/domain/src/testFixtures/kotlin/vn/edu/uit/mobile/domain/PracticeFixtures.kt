package vn.edu.uit.mobile.domain

/** Authored synthetic facts; these are not student files or a human-reviewed quality benchmark. */
object PracticeFixtures {
    val source = StudySource("module:1", "Transactions · synthetic lesson", "A database transaction preserves consistency. Atomicity means all operations complete or none do. A stack removes the most recently added item first. A queue removes the earliest added item first.")
    val vietnamese = StudySource("module:vi", "Bài học dữ liệu mẫu", "Một giao dịch cơ sở dữ liệu bảo toàn tính nhất quán. Tính nguyên tử nghĩa là tất cả thao tác hoàn tất hoặc không thao tác nào được thực hiện. Ngăn xếp lấy phần tử được thêm gần nhất trước. Hàng đợi lấy phần tử được thêm sớm nhất trước.")
    val mcq = PracticeQuestion("q1", PracticeKind.MCQ, "What does a database transaction preserve?", listOf("Consistency", "Network latency", "Screen resolution", "Disk capacity"), 0, "Consistency", "The lesson explicitly states that a transaction preserves consistency.", listOf(StudyCitation(source.id, "A database transaction preserves consistency.")))
    val card = PracticeQuestion("q2", PracticeKind.FLASHCARD, "What does atomicity require?", emptyList(), -1, "All operations complete or none do.", "Atomicity treats the operations as one unit.", listOf(StudyCitation(source.id, "Atomicity means all operations complete or none do.")))
    val generation = PracticeGeneration("synthetic-practice-model", 1793386800, "ready", "", listOf(mcq, card))
    fun set(id: String = "set-one", owner: String = "owner") = PracticeSet(id, owner, 1, "IT004", "Database review", generation.model, generation.generatedAt, "English", listOf(source), generation.questions)
}

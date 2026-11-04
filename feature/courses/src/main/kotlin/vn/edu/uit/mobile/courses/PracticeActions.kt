package vn.edu.uit.mobile.courses

import vn.edu.uit.mobile.domain.*

interface PracticeActions {
    fun close() {}
    fun bank() {}
    fun selection() {}
    fun reload() {}
    fun select(id: String) {}
    fun options(value: PracticeOptions) {}
    fun excerpt(title: String, text: String, done: () -> Unit) {}
    fun configure(mode: StudyConnectionMode, endpoint: String, secret: String, model: String, done: () -> Unit) {}
    fun disconnect(done: () -> Unit) {}
    fun generate() {}
    fun cancel() {}
    fun title(value: String) {}
    fun edit(question: PracticeQuestion, done: () -> Unit) {}
    fun removeQuestion(id: String) {}
    fun reorder(id: String, offset: Int) {}
    fun discard() {}
    fun save() {}
    fun remove(record: PracticeRecord, done: () -> Unit) {}
    fun start(record: PracticeRecord, missedOnly: Boolean = false) {}
    fun choose(index: Int) {}
    fun reveal() {}
    fun answer(recalled: Boolean? = null) {}
    fun retryAttempt() {}
    fun next() {}
}

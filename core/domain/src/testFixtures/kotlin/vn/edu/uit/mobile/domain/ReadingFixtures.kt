package vn.edu.uit.mobile.domain

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import vn.edu.uit.mobile.model.*

/** Authored three-page vector PDF; no downloaded lecture or student content. */
object ReadingFixtures {
    val course = Course(1, "owner", "IT003", "Data structures and algorithms")
    val file = CourseFile("Graph traversal.pdf", "https://example.invalid/synthetic.pdf")
    val pdf: ByteArray = createPdf()
    val identity = ReadingIdentity(course.accountId, offlineDownloadId(course, file), MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) })
    val initial get() = ReadingRecord(identity, file.name, course.id, 3)
    val note = ReadingNote("synthetic-note", 2, "Compare the stack state with the previous BFS queue.", .66f, .36f, 1793586000, 1793586000)
    fun createPdf(): ByteArray {
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>")
        val headings = listOf("Breadth-first search", "Depth-first search", "Complexity and invariants")
        val subtitles = listOf("Use a queue to visit the graph level by level.", "Use a stack or recursion to explore one branch first.", "With adjacency lists, traversal visits vertices and edges.")
        val rows = listOf(listOf("Queue: [A] -> [B, C] -> [D]", "Mark a vertex when it enters the queue.", "A visited set prevents repeated work."), listOf("Stack: [A] -> [C, B] -> [C, D]", "Track visited vertices before following an edge.", "Traversal order depends on neighbor ordering."), listOf("Time: O(V + E) for an adjacency-list traversal.", "Auxiliary space: O(V) for visited state and frontier.", "Disconnected graphs require additional start vertices."))
        for (page in 1..3) {
            val stream = buildString {
                append("0.03 0.41 0.71 rg 32 552 356 6 re f\n")
                append("BT /F1 11 Tf 32 530 Td (UIT / IT003 / Synthetic study material) Tj ET\n")
                append("BT /F1 22 Tf 32 492 Td (${headings[page - 1]}) Tj ET\n")
                append("BT /F1 10 Tf 32 470 Td (${subtitles[page - 1]}) Tj ET\n")
                append("0.65 0.72 0.78 RG 1 w 52 428 m 368 428 l S\n")
                append("0.03 0.41 0.71 RG 1.5 w 92 354 m 200 392 l 316 354 l S 92 354 m 200 302 l 316 354 l S\n")
                for ((label, xy) in listOf("A" to (186 to 378), "B" to (78 to 340), "C" to (302 to 340), "D" to (186 to 288))) {
                    append("0.92 0.96 0.99 rg ${xy.first} ${xy.second} 28 28 re f\n")
                    append("0.03 0.41 0.71 RG ${xy.first} ${xy.second} 28 28 re S\n")
                    append("0.06 0.16 0.26 rg BT /F1 13 Tf ${xy.first + 9} ${xy.second + 8} Td ($label) Tj ET\n")
                }
                rows[page - 1].forEachIndexed { index, row ->
                    val escaped = row.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                    append("BT /F1 11 Tf 32 ${240 - index * 30} Td ($escaped) Tj ET\n")
                }
                append("0.4 0.46 0.5 rg BT /F1 9 Tf 32 48 Td (Authored fixture / No university account data) Tj ET\n")
                append("BT /F1 10 Tf 354 48 Td ($page / 3) Tj ET\n")
            }
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 594] /Resources << /Font << /F1 9 0 R >> >> /Contents ${objects.size + 2} 0 R >>"
            objects += "<< /Length ${stream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$stream" + "endstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val output = ByteArrayOutputStream()
        fun append(text: String) {
            output.write(text.toByteArray(Charsets.US_ASCII))
        }
        append("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, value ->
            val offset = output.size()
            append("${index + 1} 0 obj\n$value\nendobj\n")
            offset
        }
        val xref = output.size()
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { append(it.toString().padStart(10, '0') + " 00000 n \n") }
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }
}

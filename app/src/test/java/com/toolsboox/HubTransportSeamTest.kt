package com.toolsboox

import com.toolsboox.plugin.calendar.nw.HubTransport
import com.toolsboox.plugin.calendar.nw.putBytesEnsuringFolders
import com.toolsboox.plugin.calendar.nw.putFileCommitted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * The backend seam, proven with no server at all — which is the point of a seam.
 *
 * These tests drive the two shared transfer sequences ([putFileCommitted], the library's
 * `.part`-then-rename commit; [putBytesEnsuringFolders], the sidecars' ensure-ancestors-on-409
 * retry) against a transport that is just a map. If the sequences hold here, they hold identically
 * over WebDAV and over Drive, because LibraryHub and LedgerSidecarSync only ever speak through the
 * interface — transport-blindness is not a claim in a comment, it is what these tests compile
 * against. The WebDAV implementation itself stays untested here on purpose: it is a 1:1 delegation
 * whose correctness is "the 332 existing tests didn't notice the refactor".
 */
class HubTransportSeamTest {

    /**
     * An in-memory remote that also keeps a journal of every call, because HALF of what the commit
     * sequence promises is about ORDER (the final name must never exist un-whole), and order can
     * only be asserted against a record of what happened.
     */
    private class FakeTransport(
        var moveSupported: Boolean = true,
        var putFileFails: Boolean = false,
        var failPutUntilFolderExists: Boolean = false
    ) : HubTransport {
        val files = LinkedHashMap<String, ByteArray>()
        val folders = LinkedHashSet<String>()
        val journal = mutableListOf<String>()

        override fun get(path: String): ByteArray? = files[path]

        override fun getStream(path: String, sink: (InputStream) -> Unit): Boolean {
            val bytes = files[path] ?: return false
            return runCatching { bytes.inputStream().use(sink) }.isSuccess
        }

        override fun putBytes(path: String, bytes: ByteArray, contentType: String): Boolean {
            val parent = path.substringBeforeLast('/', "")
            if (failPutUntilFolderExists && parent.isNotEmpty() && parent !in folders) {
                journal += "put-409 $path"
                return false
            }
            journal += "put $path"
            files[path] = bytes
            return true
        }

        override fun putFile(path: String, file: File): Boolean {
            if (putFileFails) return false
            journal += "putFile $path"
            files[path] = file.readBytes()
            return true
        }

        override fun ensureFolder(dirPath: String): Boolean {
            journal += "ensure $dirPath"
            folders += dirPath
            return true
        }

        override fun move(fromPath: String, toPath: String): Boolean {
            if (!moveSupported) return false
            val bytes = files.remove(fromPath) ?: return false
            journal += "move $fromPath -> $toPath"
            files[toPath] = bytes
            return true
        }

        override fun delete(path: String): Boolean {
            journal += "delete $path"
            files.remove(path)
            return true
        }

        override fun reachable(): Boolean = true
    }

    private fun tempBook(content: String): File =
        File.createTempFile("seam", ".epub").apply { writeText(content); deleteOnExit() }

    // ── putFileCommitted — the library's commit ───────────────────────────────────────────────

    @Test
    fun `committed upload lands whole under the final name and sweeps the part`() {
        val remote = FakeTransport()
        val ok = remote.putFileCommitted("books/Ashtanga", "Light on Yoga.epub", tempBook("the whole book"))
        assertTrue(ok)
        assertEquals("the whole book", remote.files["books/Ashtanga/Light on Yoga.epub"]?.decodeToString())
        assertNull("the temp must not survive the commit", remote.files["books/Ashtanga/.Light on Yoga.epub.part"])
    }

    @Test
    fun `the final name is only ever written by the rename, never by the upload`() {
        // The civility itself: a fetch racing this upload may see the part or see nothing, but the
        // name it would actually ask for appears in one atomic step. The journal is the proof.
        val remote = FakeTransport()
        remote.putFileCommitted("books", "big.pdf", tempBook("bytes"))
        assertEquals(
            listOf("putFile books/.big.pdf.part", "move books/.big.pdf.part -> books/big.pdf"),
            remote.journal
        )
    }

    @Test
    fun `a remote without rename falls back to a direct put and still sweeps its temp`() {
        val remote = FakeTransport(moveSupported = false)
        val ok = remote.putFileCommitted("books", "big.pdf", tempBook("bytes"))
        assertTrue(ok)
        assertEquals("bytes", remote.files["books/big.pdf"]?.decodeToString())
        assertNull(remote.files["books/.big.pdf.part"])
        assertTrue("the sweep must actually run", remote.journal.contains("delete books/.big.pdf.part"))
    }

    @Test
    fun `an upload that cannot land reports false and leaves no final name`() {
        val remote = FakeTransport(putFileFails = true)
        assertFalse(remote.putFileCommitted("books", "big.pdf", tempBook("bytes")))
        assertNull(remote.files["books/big.pdf"])
    }

    @Test
    fun `a root-level file commits without inventing a leading slash`() {
        // dirPath "" is a real caller shape (shelf-root books); "$dirPath/$name" done naively
        // would ask the remote for "/name", which is a different path on every backend.
        val remote = FakeTransport()
        assertTrue(remote.putFileCommitted("", "solo.epub", tempBook("b")))
        assertEquals("b", remote.files["solo.epub"]?.decodeToString())
    }

    // ── putBytesEnsuringFolders — the sidecars' push ──────────────────────────────────────────

    @Test
    fun `the steady state is one round trip with no folder chatter`() {
        val remote = FakeTransport()
        assertTrue(remote.putBytesEnsuringFolders("listen-state.json", "{}".toByteArray()))
        assertEquals(listOf("put listen-state.json"), remote.journal)
    }

    @Test
    fun `a missing ancestry is built root-first and the push retried once`() {
        // The grid-index/ lesson, replayed against the seam: stock Apache 409s a PUT under a
        // collection that was never MKCOLed, and the fix was ensure-then-retry — which now has to
        // hold on whatever backend is underneath, not just the server that taught it to us.
        val remote = FakeTransport(failPutUntilFolderExists = true)
        assertTrue(remote.putBytesEnsuringFolders("write-index/2026/pages.json", "[]".toByteArray()))
        assertEquals(
            listOf(
                "put-409 write-index/2026/pages.json",
                "ensure write-index",
                "ensure write-index/2026",
                "put write-index/2026/pages.json"
            ),
            remote.journal
        )
    }

    @Test
    fun `a root-level push that fails has no ancestry to build and says so`() {
        val remote = FakeTransport(putFileFails = false)
        // Force the put to fail by making it folder-gated with no folder to gate on: simplest is
        // a transport that refuses everything at the root.
        val refusing = object : HubTransport by remote {
            override fun putBytes(path: String, bytes: ByteArray, contentType: String) = false
        }
        assertFalse(refusing.putBytesEnsuringFolders("top.json", "{}".toByteArray()))
        assertTrue("no ensure calls for a path with no parents", remote.journal.none { it.startsWith("ensure") })
    }
}

package com.toolsboox

import com.toolsboox.ot.HubSubFold
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slug is a promise: the hub remembers a sub-fold by its row's drawn label, so the key must
 * hold still while the label's dressing — emoji, spacing, case — is free to change. These pin
 * the two labels that actually persist today (Mail, Later List) and the invariances the promise
 * rests on; break one and somebody's open folds silently snap shut on the next build.
 */
class HubSubFoldTest {

    @Test
    fun `the two shipping rows slug to their words alone`() {
        assertEquals("hub_sub_open_mail", HubSubFold.prefKey("📧  Mail"))
        assertEquals("hub_sub_open_later_list", HubSubFold.prefKey("🔖  Later List"))
    }

    @Test
    fun `emoji and spacing are styling, not identity`() {
        // A re-glyphed icon, a respaced label, or a bare label all land on the same key.
        assertEquals(HubSubFold.prefKey("📧  Mail"), HubSubFold.prefKey("✉  Mail"))
        assertEquals(HubSubFold.prefKey("🔖  Later List"), HubSubFold.prefKey("🔖 Later  List"))
        assertEquals(HubSubFold.prefKey("📧  Mail"), HubSubFold.prefKey("Mail"))
    }

    @Test
    fun `case is styling too`() {
        assertEquals(HubSubFold.prefKey("LATER LIST"), HubSubFold.prefKey("Later List"))
    }
}

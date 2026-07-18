package com.toolsboox.plugin.calendar.ot

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.toolsboox.plugin.calendar.da.v2.Contact

/**
 * Import the device address book (ContactsContract — iCloud / Google / local accounts configured on
 * the device) into the rolodex. Captures each contact's stable LOOKUP_KEY as `googleId`, back-links
 * existing rolodex entries (match by name + phone/email) so nothing duplicates, and parses birthdays.
 * Read-only to the device; writes only to the synced [ContactStore]. Requires READ_CONTACTS.
 * Mirrors the iOS CNContactStore import.
 */
object DeviceContactImport {

    /** Returns (added, backLinked). Run off the main thread. */
    fun importFromDevice(context: Context): Pair<Int, Int> {
        val cr = context.contentResolver
        val phones = firstOf(cr, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, null)
        val emails = firstOf(cr, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, null)
        val orgs = firstOf(
            cr, ContactsContract.Data.CONTENT_URI, ContactsContract.CommonDataKinds.Organization.COMPANY,
            "${ContactsContract.Data.MIMETYPE}='${ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE}'"
        )
        val bdays = birthdays(cr)

        val all = ContactStore.loadAll(context)
        var added = 0
        var linked = 0

        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            null, null, null
        )?.use { cur ->
            val idIdx = cur.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val lookupIdx = cur.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = cur.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cur.moveToNext()) {
                val id = cur.getLong(idIdx)
                val lookup = cur.getString(lookupIdx) ?: continue
                val name = cur.getString(nameIdx) ?: ""
                val phone = phones[id].orEmpty()
                val email = emails[id].orEmpty()
                val org = orgs[id].orEmpty()
                val bday = bdays[id].orEmpty()
                if (name.isBlank() && phone.isBlank() && email.isBlank()) continue

                // Already linked to this device contact → nothing to do.
                if (all.any { it.googleId == lookup && !it.isDeleted }) continue

                // A matching rolodex contact with no device link yet → back-link it in place (no duplicate).
                val m = all.indexOfFirst {
                    !it.isDeleted && it.googleId.isBlank() && name.isNotBlank() &&
                        it.name == name && (it.phone == phone || it.email == email)
                }
                if (m >= 0) {
                    all[m].googleId = lookup
                    all[m].updated = System.currentTimeMillis()
                    linked++
                    continue
                }

                all.add(
                    Contact(
                        name = name.ifBlank { email.ifBlank { phone } },
                        phone = phone, email = email, org = org, birthday = bday, googleId = lookup
                    )
                )
                added++
            }
        }

        if (added > 0 || linked > 0) {
            ContactStore.saveAll(context, all)
            ContactStore.sync(context)
        }
        return added to linked
    }

    /** First non-blank value per CONTACT_ID from a Data-backed table (optionally mimetype-filtered). */
    private fun firstOf(cr: ContentResolver, uri: Uri, valueCol: String, selection: String?): Map<Long, String> {
        val map = HashMap<Long, String>()
        cr.query(uri, arrayOf(ContactsContract.Data.CONTACT_ID, valueCol), selection, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val vIdx = c.getColumnIndexOrThrow(valueCol)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val v = c.getString(vIdx)
                if (!v.isNullOrBlank() && !map.containsKey(id)) map[id] = v
            }
        }
        return map
    }

    /** Birthdays (Event data, TYPE_BIRTHDAY) as "MMM d". */
    private fun birthdays(cr: ContentResolver): Map<Long, String> {
        val map = HashMap<Long, String>()
        val sel = "${ContactsContract.Data.MIMETYPE}='${ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE}' AND " +
            "${ContactsContract.CommonDataKinds.Event.TYPE}=${ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY}"
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Event.START_DATE),
            sel, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val dIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.START_DATE)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val raw = c.getString(dIdx) ?: continue
                val formatted = formatBirthday(raw)
                if (formatted.isNotBlank() && !map.containsKey(id)) map[id] = formatted
            }
        }
        return map
    }

    /** Turn "yyyy-MM-dd" or "--MM-dd" into "MMM d". */
    private fun formatBirthday(raw: String): String {
        val months = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val parts = raw.trim().trimStart('-').split("-")
        val (mm, dd) = when (parts.size) {
            3 -> parts[1] to parts[2]
            2 -> parts[0] to parts[1]
            else -> return ""
        }
        val m = mm.toIntOrNull() ?: return ""
        val d = dd.toIntOrNull() ?: return ""
        if (m < 1 || m > 12) return ""
        return "${months[m]} $d"
    }
}

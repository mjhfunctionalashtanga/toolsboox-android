package com.toolsboox.plugin.calendar.ot

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
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

    // ---- Push (rolodex → device address book) --------------------------------------------------
    // NON-DESTRUCTIVE: on existing device contacts we only ADD a phone/email that isn't already
    // there and fill an empty name/org — we never delete or overwrite a value the device already
    // has. Contacts with no `googleId` (added in the rolodex, not yet on the device) are created.
    // Mirrors the iOS ContactImporter.pushToApple contract. Requires WRITE_CONTACTS.

    /** Counts for the confirmation dialog: (existing to update, new to create). Read-only. */
    fun pushCounts(context: Context): Pair<Int, Int> {
        val live = ContactStore.loadAll(context).filter { !it.isDeleted && it.name.isNotBlank() }
        val update = live.count { it.googleId.isNotBlank() }
        val create = live.count { it.googleId.isBlank() }
        return update to create
    }

    /** Write the rolodex to the device. Returns (updated, created). Run off the main thread. */
    fun pushToDevice(context: Context): Pair<Int, Int> {
        val cr = context.contentResolver
        val all = ContactStore.loadAll(context)
        var updated = 0
        var created = 0
        var mutated = false

        for (i in all.indices) {
            val c = all[i]
            if (c.isDeleted || c.name.isBlank()) continue
            if (c.googleId.isNotBlank()) {
                val contactId = contactIdForLookup(cr, c.googleId)
                if (contactId == null) continue // removed on device — leave the rolodex link as-is
                val rawId = firstRawContactId(cr, contactId) ?: continue
                if (updateDeviceContact(cr, contactId, rawId, c)) updated++
            } else {
                val lookup = createDeviceContact(cr, c)
                if (lookup != null) {
                    all[i].googleId = lookup
                    all[i].updated = System.currentTimeMillis()
                    created++
                    mutated = true
                }
            }
        }

        if (mutated) {
            ContactStore.saveAll(context, all) // persist the new googleId back-links so we never re-create
            ContactStore.sync(context)
        }
        return updated to created
    }

    /** Resolve a LOOKUP_KEY to the current aggregate contact _ID (null if it's gone). */
    private fun contactIdForLookup(cr: ContentResolver, lookupKey: String): Long? {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        cr.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    /** First raw-contact row under an aggregate contact — the one we attach new Data rows to. */
    private fun firstRawContactId(cr: ContentResolver, contactId: Long): Long? {
        cr.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    /** Existing values of one Data mimetype for an aggregate contact (for the "already there?" check). */
    private fun existingValues(cr: ContentResolver, contactId: Long, mimeType: String, valueCol: String): List<String> {
        val out = ArrayList<String>()
        cr.query(
            Data.CONTENT_URI, arrayOf(valueCol),
            "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), mimeType), null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(valueCol)
            while (c.moveToNext()) c.getString(idx)?.let { out.add(it) }
        }
        return out
    }

    private fun digitsOnly(s: String) = s.filter { it.isDigit() }

    /** Add a phone/email only if absent, and fill an empty name/org. Returns whether anything changed. */
    private fun updateDeviceContact(cr: ContentResolver, contactId: Long, rawId: Long, c: Contact): Boolean {
        val ops = ArrayList<ContentProviderOperation>()

        if (c.phone.isNotBlank()) {
            val want = digitsOnly(c.phone)
            val have = existingValues(cr, contactId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER).any { digitsOnly(it) == want }
            if (!have) ops.add(insertData(rawId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, c.phone))
        }
        if (c.email.isNotBlank()) {
            val have = existingValues(cr, contactId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS).any { it.equals(c.email, true) }
            if (!have) ops.add(insertData(rawId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, c.email))
        }
        if (c.org.isNotBlank() && existingValues(cr, contactId, Organization.CONTENT_ITEM_TYPE, Organization.COMPANY).all { it.isBlank() }) {
            ops.add(insertData(rawId, Organization.CONTENT_ITEM_TYPE, Organization.COMPANY, c.org))
        }
        // Name: only fill if the device contact has no structured name at all (never overwrite).
        if (existingValues(cr, contactId, StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME).all { it.isBlank() }) {
            ops.add(insertData(rawId, StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME, c.name))
        }

        if (ops.isEmpty()) return false
        return runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ops); true }.getOrDefault(false)
    }

    /** Create a new device contact in the local (device-only) account. Returns its new LOOKUP_KEY. */
    private fun createDeviceContact(cr: ContentResolver, c: Contact): String? {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null).build()
        )
        ops.add(dataInsert(0, StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME, c.name))
        if (c.phone.isNotBlank()) ops.add(dataInsert(0, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, c.phone))
        if (c.email.isNotBlank()) ops.add(dataInsert(0, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, c.email))
        if (c.org.isNotBlank()) ops.add(dataInsert(0, Organization.CONTENT_ITEM_TYPE, Organization.COMPANY, c.org))

        val results = runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ops) }.getOrNull() ?: return null
        val rawUri = results.firstOrNull()?.uri ?: return null
        val newRawId = rawUri.lastPathSegment?.toLongOrNull() ?: return null
        // Resolve the raw contact to its aggregate contact's LOOKUP_KEY so future syncs back-link, not duplicate.
        cr.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID}=?", arrayOf(newRawId.toString()), null
        )?.use { rc ->
            if (rc.moveToFirst()) {
                val aggId = rc.getLong(0)
                cr.query(
                    ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                    "${ContactsContract.Contacts._ID}=?", arrayOf(aggId.toString()), null
                )?.use { ct -> if (ct.moveToFirst()) return ct.getString(0) }
            }
        }
        return null
    }

    /** A Data insert bound to an already-known raw contact id (for updates). */
    private fun insertData(rawId: Long, mimeType: String, valueCol: String, value: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawId)
            .withValue(Data.MIMETYPE, mimeType)
            .withValue(valueCol, value).build()

    /** A Data insert back-referencing the RawContacts insert at [backRef] (for new-contact batches). */
    private fun dataInsert(backRef: Int, mimeType: String, valueCol: String, value: String): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, backRef)
            .withValue(Data.MIMETYPE, mimeType)
            .withValue(valueCol, value).build()
}

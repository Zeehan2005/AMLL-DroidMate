package com.amll.droidmate.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class PreferenceHelperTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefsName = "test_prefs"

    @Test
    fun basicPutGetRemove() {
        val helper = PreferenceHelper(context, prefsName)
        // clean slate
        helper.clear()

        assertNull(helper.getString("key1"))
        helper.putString("key1", "value")
        assertEquals("value", helper.getString("key1"))

        helper.putBoolean("bool", true)
        assertTrue(helper.getBoolean("bool"))

        helper.putLong("long", 123L)
        assertEquals(123L, helper.getLong("long"))

        helper.remove("key1")
        assertNull(helper.getString("key1"))
    }

    @Test
    fun clearAndEdit() {
        val helper = PreferenceHelper(context, prefsName)
        helper.clear()

        helper.edit {
            putString("a", "1")
            putString("b", "2")
        }
        assertEquals("1", helper.getString("a"))
        assertEquals("2", helper.getString("b"))

        helper.clear()
        assertNull(helper.getString("a"))
        assertNull(helper.getString("b"))
    }
}

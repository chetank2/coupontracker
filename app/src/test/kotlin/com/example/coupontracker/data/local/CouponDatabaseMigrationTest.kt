package com.example.coupontracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CouponDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test-db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration8To9_preservesConfidenceBreakdownWithDefault() {
        createVersion8DatabaseWithSampleRow()

        val database = Room.databaseBuilder(context, CouponDatabase::class.java, dbName)
            .addMigrations(
                CouponDatabase.MIGRATION_8_9,
                CouponDatabase.MIGRATION_9_10,
                CouponDatabase.MIGRATION_10_11,
                CouponDatabase.MIGRATION_11_12
            )
            .allowMainThreadQueries()
            .build()

        database.openHelper.writableDatabase.use { migratedDb ->
            assertColumnExistsWithDefault(migratedDb)
            assertExistingRowsReceiveDefault(migratedDb)
        }

        database.close()
    }

    private fun createVersion8DatabaseWithSampleRow() {
        context.deleteDatabase(dbName)

        val schema = loadSchemaJson(8)
        val openHelperFactory = FrameworkSQLiteOpenHelperFactory()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    applySchema(schema, db)
                    seedCouponRow(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Not needed for tests
                }
            })
            .build()

        val helper = openHelperFactory.create(configuration)
        helper.writableDatabase.close()
        helper.close()
    }

    private fun applySchema(schema: JSONObject, db: SupportSQLiteDatabase) {
        val database = schema.getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
            db.execSQL(createSql)
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (i in 0 until indices.length()) {
                val indexSql = indices.getJSONObject(i)
                    .getString("createSql")
                    .replace("\${TABLE_NAME}", tableName)
                db.execSQL(indexSql)
            }
        }

        val setupQueries = database.optJSONArray("setupQueries") ?: JSONArray()
        for (i in 0 until setupQueries.length()) {
            val rawQuery = setupQueries.getString(i)
            val resolvedQuery = if (rawQuery.contains("\${TABLE_NAME}")) {
                val tableName = database.getJSONArray("entities")
                    .getJSONObject(0)
                    .getString("tableName")
                rawQuery.replace("\${TABLE_NAME}", tableName)
            } else {
                rawQuery
            }
            db.execSQL(resolvedQuery)
        }
    }

    private fun seedCouponRow(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO coupons (
                storeName,
                description,
                cashbackAmount,
                isPriority,
                usageCount,
                createdAt,
                updatedAt
            ) VALUES (
                'Just',
                'Sample description',
                0.0,
                0,
                0,
                0,
                0
            )
            """.trimIndent()
        )
    }

    private fun assertColumnExistsWithDefault(db: SupportSQLiteDatabase) {
        db.query("PRAGMA table_info(`coupons`)").use { cursor ->
            var found = false
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            val defaultIndex = cursor.getColumnIndex("dflt_value")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals("extractionConfidenceBreakdown", ignoreCase = true)) {
                    found = true
                    val isNotNull = cursor.getInt(notNullIndex)
                    val defaultValue = cursor.getString(defaultIndex)
                    assertEquals(1, isNotNull)
                    assertEquals("'{}'", defaultValue)
                }
            }
            assertTrue("Expected extractionConfidenceBreakdown column to exist", found)
        }
    }

    private fun assertExistingRowsReceiveDefault(db: SupportSQLiteDatabase) {
        db.query("SELECT extractionConfidenceBreakdown FROM coupons").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
        }
    }

    private fun loadSchemaJson(version: Int): JSONObject {
        val directPath = File("schemas/com.example.coupontracker.data.local.CouponDatabase/$version.json")
        if (directPath.exists()) {
            return JSONObject(directPath.readText())
        }
        val modulePath = File("app/schemas/com.example.coupontracker.data.local.CouponDatabase/$version.json")
        if (modulePath.exists()) {
            return JSONObject(modulePath.readText())
        }
        throw IllegalStateException("Schema file for version $version not found")
    }
}

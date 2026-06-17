package com.qalens.sample

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A real on-disk SQLite database (what a Room app would have under the hood) so QaLens's
 * Database tooling — raw queries, rows-affected reporting, saved queries — is demoable in the
 * sample. Seeded once with the same accounts/transactions the fake backend shows.
 */
class SampleDatabase(context: Context) : SQLiteOpenHelper(context, "sample.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE accounts (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                balance REAL NOT NULL,
                masked_number TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL,
                merchant TEXT NOT NULL,
                category TEXT NOT NULL,
                amount REAL NOT NULL,
                pending INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("INSERT INTO accounts VALUES (1,'Main Current','current',24580.0,'•••• 8901')")
        db.execSQL("INSERT INTO accounts VALUES (2,'Savings Goal','savings',87200.0,'•••• 2555')")
        db.execSQL("INSERT INTO accounts VALUES (3,'Investment','investment',142900.0,'•••• 0000')")
        db.execSQL("INSERT INTO accounts VALUES (4,'Daily Spending','current',3250.5,'•••• 1234')")
        listOf(
            "(1,'Starbucks','cafe',-45.0,0)",
            "(1,'Carrefour','groceries',-380.0,0)",
            "(1,'Netflix','entertainment',-55.0,0)",
            "(2,'Transfer In','transfer',2000.0,0)",
            "(2,'Auto-save','rule',500.0,0)",
            "(4,'Uber','transport',-32.5,1)"
        ).forEach { db.execSQL("INSERT INTO transactions (account_id,merchant,category,amount,pending) VALUES $it") }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        /** Open (and seed on first run) so the DB file exists for the QaLens Database card. */
        fun warmUp(context: Context) {
            runCatching { SampleDatabase(context).readableDatabase.close() }
        }
    }
}

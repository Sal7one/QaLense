package com.qalens.sample;

import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.PrimaryKey;
import androidx.room.RoomDatabase;

/** Tiny device-only Room fixture; the sample app's normal database remains SQLite. */
@Database(entities = FixtureRoomDatabase.Entry.class, version = 1, exportSchema = false)
public abstract class FixtureRoomDatabase extends RoomDatabase {
    public abstract EntryDao entries();

    @Entity(tableName = "entries")
    public static class Entry {
        @PrimaryKey public long id;
        public String value;

        public Entry(long id, String value) {
            this.id = id;
            this.value = value;
        }
    }

    @Dao
    public interface EntryDao {
        @Insert void insert(Entry entry);
    }
}

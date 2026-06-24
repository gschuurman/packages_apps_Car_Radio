/**
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.radio.storage;

import android.content.Context;
import android.hardware.radio.ProgramSelector;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.android.car.broadcastradio.support.Program;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Radio app database schema.
 *
 * This class should not be accessed directly.
 * Instead, {@link RadioStorage} interfaces directly with it.
 */
@Database(entities = {Favorite.class, CachedStation.class}, exportSchema = false, version = 2)
@TypeConverters({ProgramSelectorConverter.class})
abstract class RadioDatabase extends RoomDatabase {
    @Dao
    protected interface FavoriteDao {
        @Query("SELECT * FROM Favorite ORDER BY primaryId_type, primaryId_value")
        LiveData<List<Favorite>> loadAll();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertAll(Favorite... favorites);

        @Query("DELETE FROM Favorite WHERE "
                + "primaryId_type = :primaryIdType AND primaryId_value = :primaryIdValue")
        void delete(int primaryIdType, long primaryIdValue);

        default void delete(@NonNull ProgramSelector.Identifier id) {
            delete(id.getType(), id.getValue());
        }
    }

    @Dao
    protected interface StationDao {
        @Query("SELECT * FROM CachedStation ORDER BY sortIndex")
        LiveData<List<CachedStation>> loadAll();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertAll(List<CachedStation> stations);

        @Query("DELETE FROM CachedStation")
        void deleteAll();

        @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM CachedStation")
        int maxSortIndex();

        /** Atomically replaces the cached list with {@code stations}. */
        @Transaction
        default void replaceAll(@NonNull List<CachedStation> stations) {
            deleteAll();
            insertAll(stations);
        }

        /** Appends {@code stations} after the existing rows (upserting on selector conflict). */
        @Transaction
        default void appendAll(@NonNull List<CachedStation> stations) {
            insertAll(stations);
        }
    }

    protected abstract FavoriteDao favoriteDao();

    protected abstract StationDao stationDao();

    /**
     * v1 → v2: adds the {@link CachedStation} table (browse-list cache). Hand-written so the
     * existing {@link Favorite} table — and the user's favorites — survive the upgrade.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `CachedStation` ("
                    + "`selector` TEXT NOT NULL, "
                    + "`name` TEXT NOT NULL, "
                    + "`sortIndex` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`selector`))");
        }
    };

    public static RadioDatabase buildInstance(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), RadioDatabase.class,
                RadioDatabase.class.getSimpleName())
                .addMigrations(MIGRATION_1_2)
                .enableMultiInstanceInvalidation()
                .build();
    }

    /**
     * Returns a list of all user stored radio favorites sorted by primary identifier.
     */
    @WorkerThread
    @NonNull
    public LiveData<List<Program>> getAllFavorites() {
        return Transformations.map(favoriteDao().loadAll(), favorites ->
                favorites.stream().map(Favorite::toProgram).collect(Collectors.toList()));
    }

    /**
     * Saves a given {@link Program} as a favorite.
     *
     * The favorite will replace any existing entry for a given primary
     * identifier if there is a conflict.
     */
    @WorkerThread
    public void insertFavorite(@NonNull Program favorite) {
        favoriteDao().insertAll(new Favorite(favorite));
    }

    /**
     * Removes a favorite by primary id of its {@link ProgramSelector}.
     */
    @WorkerThread
    public void removeFavorite(@NonNull ProgramSelector sel) {
        favoriteDao().delete(sel.getPrimaryId());
    }

    /**
     * Returns the persisted Browse station list (last-seen program list), in discovery order.
     */
    @WorkerThread
    @NonNull
    public LiveData<List<Program>> getAllStations() {
        return Transformations.map(stationDao().loadAll(), stations ->
                stations.stream().map(CachedStation::toProgram).collect(Collectors.toList()));
    }

    /**
     * Replaces the persisted Browse station list with {@code programs} (order preserved).
     */
    @WorkerThread
    public void saveStations(@NonNull List<Program> programs) {
        List<CachedStation> rows = new ArrayList<>(programs.size());
        for (int i = 0; i < programs.size(); i++) {
            rows.add(new CachedStation(programs.get(i), i));
        }
        stationDao().replaceAll(rows);
    }

    /**
     * Appends {@code programs} to the persisted Browse station list, after the existing entries
     * (selector conflicts upsert in place). Used for additive write-through of live stations.
     */
    @WorkerThread
    public void addStations(@NonNull List<Program> programs) {
        int base = stationDao().maxSortIndex() + 1;
        List<CachedStation> rows = new ArrayList<>(programs.size());
        for (int i = 0; i < programs.size(); i++) {
            rows.add(new CachedStation(programs.get(i), base + i));
        }
        stationDao().appendAll(rows);
    }

    /**
     * Clears the persisted Browse station list.
     */
    @WorkerThread
    public void clearStations() {
        stationDao().deleteAll();
    }
}

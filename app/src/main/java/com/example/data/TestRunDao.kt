package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TestRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: TestRunEntity): Long

    @Query("SELECT * FROM test_runs ORDER BY timestamp DESC")
    fun getAllRuns(): Flow<List<TestRunEntity>>

    @Query("SELECT * FROM test_runs WHERE testCategory = :category ORDER BY timestamp DESC")
    fun getRunsByCategory(category: String): Flow<List<TestRunEntity>>

    @Query("SELECT * FROM test_runs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRuns(limit: Int): Flow<List<TestRunEntity>>

    @Query("SELECT COUNT(*) FROM test_runs")
    suspend fun getCount(): Int

    @Query("DELETE FROM test_runs")
    suspend fun clearAll()
}

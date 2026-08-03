package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorkRuleDao {
    @Query("SELECT * FROM work_rules WHERE book_id = :bookId ORDER BY sort ASC")
    suspend fun getRules(bookId: String): List<WorkRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: WorkRuleEntity)

    @Query("DELETE FROM work_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM work_rules WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: String)
}

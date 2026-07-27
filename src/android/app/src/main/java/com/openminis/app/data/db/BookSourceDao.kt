package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg sources: BookSourceEntity)

    @Query("SELECT * FROM book_sources ORDER BY bookSourceName ASC")
    suspend fun getAll(): List<BookSourceEntity>

    @Query("SELECT * FROM book_sources WHERE bookSourceUrl = :url LIMIT 1")
    suspend fun getByUrl(url: String): BookSourceEntity?

    @Query("DELETE FROM book_sources WHERE bookSourceUrl = :url")
    suspend fun deleteByUrl(url: String)
}

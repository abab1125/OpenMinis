package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_rules")
data class WorkRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val content: String,
    val sort: Int,
)

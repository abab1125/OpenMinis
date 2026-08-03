package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_rules")
data class WorkRuleEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val content: String,
    val sort: Int,
)

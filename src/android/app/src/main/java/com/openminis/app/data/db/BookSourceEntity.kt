package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A legado-format book source (e.g. lingya). Stored independently from the
 * file-based novel projects in [com.openminis.app.data.repository.BookRepository]
 * so importing remote sources never touches the local book filesystem.
 *
 * Rule objects (ruleExplore / ruleSearch / ...) are kept as raw JSON strings
 * and parsed on demand by [com.openminis.app.data.repository.BookSourceRepository].
 */
@Entity(tableName = "book_sources")
data class BookSourceEntity(
    @PrimaryKey val bookSourceUrl: String,
    val bookSourceName: String,
    val bookSourceGroup: String? = null,
    val enabledExplore: Boolean = true,
    /** Multi-line `categoryName::url` entries; `{{page}}`/`{{genre}}` are template vars. */
    val exploreUrl: String,
    /** JSON of the ExploreRule (bookList / name / author / coverUrl / bookUrl / ...). */
    val ruleExploreJson: String,
    val ruleSearchJson: String? = null,
    val ruleBookInfoJson: String? = null,
    val ruleTocJson: String? = null,
    val ruleContentJson: String? = null,
    /** Optional request headers, legado "key:value" newline format. */
    val header: String? = null,
    val lastUpdateTime: Long = 0,
)

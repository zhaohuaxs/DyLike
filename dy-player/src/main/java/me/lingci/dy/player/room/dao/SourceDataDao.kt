package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.SourceData

/**
 * SourceData DAO接口
 *
 * Room 3：DAO 函数必须 suspend 或返回 Flow
 */
@Dao
interface SourceDataDao {

    @Query("SELECT count(*) FROM source_data")
    suspend fun count(): Int

    @Query("SELECT * FROM source_data")
    suspend fun list(): List<SourceData>

    /** 响应式查询存储源列表（替代 LibraryCompat.loadSources） */
    @Query("SELECT * FROM source_data")
    fun observeAll(): Flow<List<SourceData>>

    @Query("SELECT * FROM source_data WHERE id = :id")
    suspend fun getById(id: String): SourceData?

    @Insert
    suspend fun insert(sourceData: SourceData)

    /** 批量插入（迁移用） */
    @Insert
    suspend fun insertAll(sourceList: List<SourceData>)

    /** 迁移合并插入：同主键行跳过，返回逐行 rowId（-1 表示未插入） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(sourceList: List<SourceData>): LongArray

    @Update
    suspend fun update(sourceData: SourceData)

    @Delete
    suspend fun delete(sourceData: SourceData)

}

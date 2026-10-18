package me.lingci.dy.player.migrate

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityMigrationBinding

/**
 * SP→Room 数据迁移页（§5.4）。
 *
 * - 进入时展示旧数据七项统计
 * - 迁移期间屏蔽返回键（事务整体回滚，避免误触中断）
 * - 成功后显示统计；跳过的随机播放状态 >0 时给出提示
 */
class MigrationActivity : AppCompatActivity() {

    private val viewModel: MigrationViewModel by viewModels()
    private lateinit var binding: ActivityMigrationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMigrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 迁移期间屏蔽返回键（含手势返回），避免中途取消导致半迁移观感
        onBackPressedDispatcher.addCallback(this) { /* 拦截，不退出 */ }

        viewModel.state.observe(this) { render(it) }
        viewModel.prepare()

        binding.btnLater.setOnClickListener { finish() }
        binding.btnStart.setOnClickListener { viewModel.startMigration() }
    }

    private fun render(state: MigrationViewModel.State) {
        when (state) {
            is MigrationViewModel.State.Idle -> Unit

            is MigrationViewModel.State.Prepared -> {
                fillStats(state.preview)
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.visibility = View.GONE
                binding.btnLater.visibility = View.VISIBLE
                binding.btnStart.isEnabled = true
                binding.btnStart.text = getString(R.string.migration_start)
                binding.btnStart.setOnClickListener { viewModel.startMigration() }
            }

            is MigrationViewModel.State.Running -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.migration_running)
                binding.btnLater.isEnabled = false
                binding.btnStart.isEnabled = false
            }

            is MigrationViewModel.State.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.migration_success)
                binding.btnLater.visibility = View.GONE
                binding.btnStart.isEnabled = true
                binding.btnStart.text = getString(R.string.migration_done)
                binding.btnStart.setOnClickListener { finish() }
                // 跳过项提示（§8.2）
                if (state.result.skippedShuffles > 0) {
                    binding.tvStatus.append("\n" + getString(
                        R.string.migration_skip_shuffle_note, state.result.skippedShuffles
                    ))
                }
                // 合并模式提示：曾点「稍后再说」并继续用过应用，库内已有同 id 数据时保留库内版本
                if (state.result.skippedExisting > 0) {
                    binding.tvStatus.append("\n" + getString(
                        R.string.migration_merge_note, state.result.skippedExisting
                    ))
                }
            }

            is MigrationViewModel.State.Failed -> {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.migration_failed, state.message)
                binding.btnLater.isEnabled = true
                binding.btnStart.isEnabled = true
                binding.btnStart.text = getString(R.string.migration_retry)
                binding.btnStart.setOnClickListener { viewModel.startMigration() }
            }
        }
    }

    /** 程序化填充七项统计行（label …… count） */
    private fun fillStats(preview: MigrationViewModel.Preview) {
        binding.statBox.removeAllViews()
        val rows = listOf(
            getString(R.string.migration_stat_sources) to preview.sources,
            getString(R.string.migration_stat_media) to preview.media,
            getString(R.string.migration_stat_videos) to preview.videos,
            getString(R.string.migration_stat_history) to preview.history,
            getString(R.string.migration_stat_favorites) to preview.favorites,
            getString(R.string.migration_stat_playlists) to
                    "${preview.playlists} 个 (${preview.playlistItems} 项)",
            getString(R.string.migration_stat_shuffles) to preview.shuffles
        )
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        rows.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp8) }
            }
            row.addView(TextView(this).apply {
                text = "○ $label"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply { text = value.toString() })
            binding.statBox.addView(row)
        }
    }
}

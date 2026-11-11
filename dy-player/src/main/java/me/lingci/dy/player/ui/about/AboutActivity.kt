package me.lingci.dy.player.ui.about

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import me.lingci.dy.player.BuildConfig
import me.lingci.dy.player.databinding.ActivityAboutBinding
import me.lingci.dy.player.ui.tool.DisplayInfoDialog
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.ToastUtil
import androidx.core.net.toUri

/**
 * 关于
 */
class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var displayInfoDialog: DisplayInfoDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
    }

    @SuppressLint("SetTextI18n")
    private fun init() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        displayInfoDialog = DisplayInfoDialog()

        binding.tvVersion.text = "当前版本 ${BuildConfig.VERSION_NAME}"
        binding.tvFeedback.setOnClickListener {
            joinQQGroup("QB6v-ccuzZgQe2jErJwcjEw_OXqfI-vf")
        }
        binding.tvPublicLicense.setOnClickListener {
            ToastUtil.showToast(this, "等待后续添加")
        }
        binding.tvAppRecord.setOnClickListener {
            ToastUtil.showToast(this, "暂未申请")
        }
        binding.tvProtocol.setOnClickListener {
            displayInfoDialog.show(supportFragmentManager, displayInfoDialog.tag)
            ToastUtil.showToast(this, "等待后续添加")
        }
    }

    private fun gotoPublicLicense() {
        val intent = Intent(this, AboutActivity::class.java)
        startActivity(intent)
    }

    /**
     *
     * 发起添加群流程。
     *
     * @param key 由官网生成的key
     * @return 返回true表示呼起手Q成功，返回false表示呼起失败
     */
    @SuppressLint("IntentWithNullActionLaunch")
    fun joinQQGroup(key: String): Boolean {
        val intent = Intent()
        intent.setData("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key".toUri())
        // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return true
        } catch (e: Exception) {
            // 未安装手Q或安装的版本不支持
            ToastUtil.showToast(this, "未安装手Q或安装的版本不支持")
            return false
        }
    }

}
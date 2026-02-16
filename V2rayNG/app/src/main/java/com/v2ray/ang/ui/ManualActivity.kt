package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityManualBinding

class ManualActivity : BaseActivity() {
    private val binding by lazy { ActivityManualBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_manual))
    }
}

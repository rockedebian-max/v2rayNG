package com.v2ray.ang.ui

import android.animation.AnimatorInflater
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            //Name address
            holder.itemMainBinding.tvName.text = profile.remarks
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = profile.configType.name

            //TestResult - color-coded ping
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            val delayMs = aff?.testDelayMillis ?: 0L
            if (delayMs > 0L) {
                holder.itemMainBinding.tvTestResult.text = "${delayMs}ms"
                val pingColor = when {
                    delayMs < 200 -> R.color.colorPingGreen
                    delayMs < 500 -> R.color.colorPingYellow
                    else -> R.color.colorPingRed
                }
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, pingColor))
            } else if (delayMs < 0L) {
                holder.itemMainBinding.tvTestResult.text = "Error"
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.text = ""
            }

            // Card + indicator styling based on selection
            val isSelected = guid == MmkvManager.getSelectServer()
            val cardView = holder.itemView as? MaterialCardView
            val indicator = holder.itemMainBinding.layoutIndicator
            if (isSelected) {
                indicator.setBackgroundResource(R.drawable.indicator_active)
                cardView?.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSelectedCard))
                cardView?.strokeColor = ContextCompat.getColor(context, R.color.colorSelectedBorder)
                cardView?.strokeWidth = 2.dpToPx(context)
                holder.itemMainBinding.tvName.setTypeface(null, android.graphics.Typeface.BOLD)
                holder.itemMainBinding.tvName.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_shield_small, 0, 0, 0
                )
                holder.itemMainBinding.tvName.compoundDrawablePadding = 6.dpToPx(context)
                // Breathing pulse on indicator
                indicator.tag?.let { (it as? android.animation.Animator)?.cancel() }
                val pulse = AnimatorInflater.loadAnimator(context, R.animator.pulse_breath)
                pulse.setTarget(indicator)
                pulse.start()
                indicator.tag = pulse
            } else {
                indicator.tag?.let { (it as? android.animation.Animator)?.cancel() }
                indicator.tag = null
                indicator.alpha = 1f
                indicator.setBackgroundResource(0)
                cardView?.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorCardBackground))
                cardView?.strokeColor = ContextCompat.getColor(context, R.color.colorCardBorder)
                cardView?.strokeWidth = 1.dpToPx(context)
                holder.itemMainBinding.tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
                holder.itemMainBinding.tvName.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }

            //subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            //layout - Phase 2: Only show remove button, hide share and edit
            holder.itemMainBinding.layoutShare.visibility = View.GONE
            holder.itemMainBinding.layoutEdit.visibility = View.GONE
            holder.itemMainBinding.layoutMore.visibility = View.GONE

            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
            } else {
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
 
    }

    /**
     * Gets the server address information
     * Hides part of IP or domain information for privacy protection
     * @param profile The server configuration
     * @return Formatted address string
     */
    private fun getAddress(profile: ProfileItem): String {
        // Phase 2: Hide server address completely
        return profile.configType.name
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            (itemView as? MaterialCardView)?.alpha = 0.7f
        }

        fun onItemClear() {
            (itemView as? MaterialCardView)?.alpha = 1.0f
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
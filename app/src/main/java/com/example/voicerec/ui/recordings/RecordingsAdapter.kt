package com.example.voicerec.ui.recordings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.voicerec.R
import com.example.voicerec.data.Recording

/**
 * 录音列表适配器（三级树形结构）
 */
class RecordingsAdapter(
    private val viewModel: RecordingsViewModel,
    private val onItemClick: (Recording) -> Unit,
    private val onItemLongClick: (Recording) -> Unit,
    private val onDayDelete: (String) -> Unit
) : ListAdapter<RecordingsAdapter.TreeItem, RecyclerView.ViewHolder>(DiffCallback()) {

    sealed class TreeItem {
        data class DayItem(
            val day: String,
            val count: Int,
            val totalDuration: Long,
            val expanded: Boolean
        ) : TreeItem()

        data class HourItem(
            val day: String,
            val hour: String,
            val count: Int,
            val expanded: Boolean
        ) : TreeItem()

        data class RecordingItem(
            val recording: Recording
        ) : TreeItem()
    }

    class DiffCallback : DiffUtil.ItemCallback<TreeItem>() {
        override fun areItemsTheSame(oldItem: TreeItem, newItem: TreeItem): Boolean {
            return when {
                oldItem is TreeItem.DayItem && newItem is TreeItem.DayItem ->
                    oldItem.day == newItem.day
                oldItem is TreeItem.HourItem && newItem is TreeItem.HourItem ->
                    oldItem.day == newItem.day && oldItem.hour == newItem.hour
                oldItem is TreeItem.RecordingItem && newItem is TreeItem.RecordingItem ->
                    oldItem.recording.id == newItem.recording.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: TreeItem, newItem: TreeItem): Boolean {
            return oldItem == newItem
        }
    }

    inner class DayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleText: TextView = view.findViewById(R.id.tv_day_title)
        private val subtitleText: TextView = view.findViewById(R.id.tv_day_subtitle)
        private val expandIcon: ImageView = view.findViewById(R.id.iv_expand)
        private val deleteIcon: ImageView = view.findViewById(R.id.iv_delete)

        fun bind(item: TreeItem.DayItem) {
            titleText.text = item.day
            subtitleText.text = "${item.count}个录音 · ${viewModel.formatDuration(item.totalDuration)}"
            expandIcon.rotation = if (item.expanded) 90f else 0f

            itemView.setOnClickListener {
                viewModel.toggleDayExpansion(item.day)
            }

            deleteIcon.setOnClickListener {
                onDayDelete(item.day)
            }
        }
    }

    inner class HourViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleText: TextView = view.findViewById(R.id.tv_hour_title)
        private val subtitleText: TextView = view.findViewById(R.id.tv_hour_subtitle)
        private val expandIcon: ImageView = view.findViewById(R.id.iv_expand)

        fun bind(item: TreeItem.HourItem) {
            titleText.text = item.hour
            subtitleText.text = "${item.count}个录音"
            expandIcon.rotation = if (item.expanded) 90f else 0f

            itemView.setOnClickListener {
                viewModel.toggleHourExpansion("${item.day}_${item.hour}")
            }
        }
    }

    inner class RecordingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleText: TextView = view.findViewById(R.id.tv_recording_title)
        private val subtitleText: TextView = view.findViewById(R.id.tv_recording_subtitle)
        private val moreIcon: ImageView = view.findViewById(R.id.iv_more)

        fun bind(item: TreeItem.RecordingItem) {
            val recording = item.recording
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(recording.timestamp))

            titleText.text = time
            subtitleText.text = "${viewModel.formatDuration(recording.durationMs)} · " +
                    viewModel.formatFileSize(recording.fileSizeBytes)

            itemView.setOnClickListener {
                onItemClick(recording)
            }

            itemView.setOnLongClickListener {
                onItemLongClick(recording)
                true
            }

            moreIcon.setOnClickListener {
                onItemLongClick(recording)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is TreeItem.DayItem -> 0
            is TreeItem.HourItem -> 1
            is TreeItem.RecordingItem -> 2
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> DayViewHolder(
                layoutInflater.inflate(R.layout.item_day, parent, false)
            )
            1 -> HourViewHolder(
                layoutInflater.inflate(R.layout.item_hour, parent, false)
            )
            else -> RecordingViewHolder(
                layoutInflater.inflate(R.layout.item_recording, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is DayViewHolder -> holder.bind(getItem(position) as TreeItem.DayItem)
            is HourViewHolder -> holder.bind(getItem(position) as TreeItem.HourItem)
            is RecordingViewHolder -> holder.bind(getItem(position) as TreeItem.RecordingItem)
        }
    }
}

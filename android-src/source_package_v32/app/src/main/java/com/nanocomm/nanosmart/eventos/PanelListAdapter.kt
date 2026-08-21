package com.nanocomm.nanosmart.eventos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class PanelListAdapter(
    private val statusProvider: (String) -> String,
    private val onOpen: (PanelConfig) -> Unit,
    private val onEdit: (PanelConfig) -> Unit,
    private val onVisible: (PanelConfig) -> Unit
) : ListAdapter<PanelConfig, PanelListAdapter.PanelViewHolder>(DIFF) {

    private var positionByImei: Map<String, Int> = emptyMap()

    fun submitPanels(panels: List<PanelConfig>) {
        positionByImei = panels.withIndex().associate { it.value.imei to it.index }
        submitList(panels)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelViewHolder {
        val item = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_panel, parent, false)
        return PanelViewHolder(item)
    }

    override fun onBindViewHolder(holder: PanelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun notifyStatusChanged(imei: String) {
        positionByImei[imei]?.let { position ->
            if (position in 0 until itemCount) notifyItemChanged(position, STATUS_PAYLOAD)
        }
    }

    fun notifyVisibleStatusesChanged() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, STATUS_PAYLOAD)
    }

    override fun onBindViewHolder(
        holder: PanelViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(STATUS_PAYLOAD)) {
            holder.bindStatus(getItem(position).imei)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PanelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name = itemView.findViewById<TextView>(R.id.txtPanelName)
        private val status = itemView.findViewById<TextView>(R.id.txtPanelStatus)
        private val abonado = itemView.findViewById<TextView>(R.id.txtPanelAbonado)
        private val details = itemView.findViewById<TextView>(R.id.txtPanelDetails)
        private val edit = itemView.findViewById<Button>(R.id.btnEditPanel)
        private val open = itemView.findViewById<Button>(R.id.btnOpenPanel)
        private var panel: PanelConfig? = null

        init {
            InteractionFeedback.install(itemView, FeedbackKind.NAVIGATION)
            InteractionFeedback.install(edit, FeedbackKind.NAVIGATION)
            InteractionFeedback.install(open, FeedbackKind.NAVIGATION)
            itemView.setOnClickListener { panel?.let(onOpen) }
            edit.setOnClickListener { panel?.let(onEdit) }
            open.setOnClickListener { panel?.let(onOpen) }
        }

        fun bind(panel: PanelConfig) {
            this.panel = panel
            name.text = panel.panelName
            abonado.visibility = if (panel.abonado.isBlank()) View.GONE else View.VISIBLE
            abonado.text = "Abonado ${panel.abonado}"
            val mode = if (panel.serviceMode == ServiceMode.SELF_MONITORING) {
                "Automonitoreo"
            } else {
                "Monitoreo"
            }
            details.text = "$mode · IMEI •••• ${panel.imei.takeLast(4)}"
            bindStatus(panel.imei)
            onVisible(panel)
        }

        fun bindStatus(imei: String) {
            val current = statusProvider(imei).uppercase()
            status.text = current
            status.setBackgroundResource(
                when (current) {
                    "ARMADO" -> R.drawable.bg_status_armed_chip
                    "DESARMADO" -> R.drawable.bg_status_disarmed_chip
                    else -> R.drawable.bg_status_unknown_chip
                }
            )
        }
    }

    private companion object {
        const val STATUS_PAYLOAD = "status"
        val DIFF = object : DiffUtil.ItemCallback<PanelConfig>() {
            override fun areItemsTheSame(oldItem: PanelConfig, newItem: PanelConfig): Boolean =
                oldItem.imei == newItem.imei

            override fun areContentsTheSame(oldItem: PanelConfig, newItem: PanelConfig): Boolean =
                oldItem == newItem
        }
    }
}

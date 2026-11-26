package cc.dripwire.vpn.buykey

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cc.dripwire.vpn.databinding.ItemPlanBinding

class PlanAdapter(
    private val plans: List<Plan>,
    private val onClick: (Plan) -> Unit,
) : RecyclerView.Adapter<PlanAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemPlanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val plan = plans[position]
        holder.binding.planNameText.text = plan.name
        holder.binding.planPriceText.text = plan.priceDisplay()
        holder.binding.root.setOnClickListener { onClick(plan) }
    }

    override fun getItemCount(): Int = plans.size
}
